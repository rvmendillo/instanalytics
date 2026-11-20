package com.rvmendillo.igstats.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.rvmendillo.igstats.auth.AccountStore.Account;
import com.rvmendillo.igstats.config.IgProperties;
import com.rvmendillo.igstats.ig.GraphApiClient;
import com.rvmendillo.igstats.ig.IgApiException;
import com.rvmendillo.igstats.ig.IgMetricCatalog.LoginType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Keeps long-lived tokens alive.
 *
 * <p>The failure mode this prevents is silent and unrecoverable: a token not
 * refreshed within 60 days cannot be refreshed at all, the collectors start
 * failing with code 190, and you lose every story posted until you notice. So
 * refresh on a schedule, early and often - never wait for a 401.
 *
 * <p>Constraint from Meta: a token must be at least 24 hours old to refresh.
 */
@Component
public class TokenRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(TokenRefreshJob.class);
    private static final Duration MIN_AGE = Duration.ofHours(24);
    private static final Duration WARN_WITHIN = Duration.ofDays(10);

    private final GraphApiClient api;
    private final AccountStore accounts;
    private final IgProperties props;

    public TokenRefreshJob(GraphApiClient api, AccountStore accounts, IgProperties props) {
        this.api = api;
        this.accounts = accounts;
        this.props = props;
    }

    /** Daily at 03:17 local - off the hour, out of the way of the story poller. */
    @Scheduled(cron = "0 17 3 * * *", zone = "${ig.zone:Asia/Manila}")
    public void run() {
        for (Account a : accounts.active()) {
            try {
                refreshIfDue(a);
            } catch (Exception e) {
                log.error("token refresh failed for @{}", a.username(), e);
            }
        }
    }

    void refreshIfDue(Account a) {
        Duration age = Duration.between(a.tokenIssuedAt(), Instant.now());
        Duration left = Duration.between(Instant.now(), a.tokenExpiresAt());

        if (left.isNegative()) {
            log.error("@{}: token already expired {} ago. Reconnect required.",
                    a.username(), left.negated().toDays() + "d");
            accounts.deactivate(a.id());
            return;
        }
        if (age.compareTo(MIN_AGE) < 0) {
            return; // Meta rejects refreshes on tokens under 24h old
        }
        if (age.toDays() < props.refreshTokenDays() && left.compareTo(WARN_WITHIN) > 0) {
            return; // not due yet
        }

        if (a.loginType() != LoginType.INSTAGRAM) {
            // Facebook Login tokens refresh through re-exchange, which needs a
            // fresh short-lived token from the user. Warn instead of guessing.
            if (left.compareTo(WARN_WITHIN) < 0) {
                log.warn("@{}: Facebook Login token expires in {}d - reconnect via /auth/facebook",
                        a.username(), left.toDays());
            }
            return;
        }

        try {
            JsonNode r = api.refreshInstagramToken(a.token());
            String token = r.path("access_token").asText();
            long expiresIn = r.path("expires_in").asLong(Duration.ofDays(60).toSeconds());
            accounts.updateToken(a.id(), token, Instant.now().plusSeconds(expiresIn));
            log.info("@{}: token refreshed, valid {}d", a.username(), Duration.ofSeconds(expiresIn).toDays());
        } catch (IgApiException e) {
            if (e.isAuthFailure()) {
                accounts.deactivate(a.id());
            }
            throw e;
        }
    }
}
