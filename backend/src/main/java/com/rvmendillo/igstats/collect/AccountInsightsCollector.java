package com.rvmendillo.igstats.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.rvmendillo.igstats.auth.AccountStore;
import com.rvmendillo.igstats.auth.AccountStore.Account;
import com.rvmendillo.igstats.config.IgProperties;
import com.rvmendillo.igstats.ig.GraphApiClient;
import com.rvmendillo.igstats.ig.GraphApiClient.InsightRow;
import com.rvmendillo.igstats.ig.IgApiException;
import com.rvmendillo.igstats.ig.IgMetricCatalog;
import com.rvmendillo.igstats.ig.IgMetricCatalog.Breakdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Daily account-level insights: views, reach, accounts engaged, interactions,
 * follows and unfollows, profile link taps - plus a dated snapshot of your
 * follower count, which is the one series Meta never gives you history for.
 *
 * <p>Each day is fetched as its own {@code total_value} window rather than as a
 * time series. Every account metric supports total_value; far fewer support
 * time_series, and one unsupported metric fails the whole request.
 *
 * <p>The last three days are re-fetched on every run because Meta's numbers can
 * lag up to 48 hours. Upserts make that free of duplicates.
 */
@Component
public class AccountInsightsCollector {

    private static final Logger log = LoggerFactory.getLogger(AccountInsightsCollector.class);
    private static final String NAME = "account";
    private static final int BACKFILL_DAYS = 14;
    private static final int REFETCH_DAYS = 3;

    private final GraphApiClient api;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final IgProperties props;
    private final CollectorRuns runs;

    public AccountInsightsCollector(GraphApiClient api, AccountStore accounts, JdbcTemplate jdbc,
                                    IgProperties props, CollectorRuns runs) {
        this.api = api;
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.props = props;
        this.runs = runs;
    }

    /** 04:23 local. Off the hour, and after midnight so "yesterday" is complete-ish. */
    @Scheduled(cron = "0 23 4 * * *", zone = "${ig.zone:Asia/Manila}")
    public void run() {
        for (Account a : accounts.active()) {
            try {
                collect(a);
            } catch (Exception e) {
                log.error("account insights failed for @{}", a.username(), e);
            }
        }
    }

    public int collect(Account a) {
        CollectorRuns.Run run = runs.start(a.id(), NAME);
        try {
            refreshProfile(a, run);

            Boolean hasHistory = jdbc.queryForObject(
                    "select exists(select 1 from account_metric_daily where account_id = ?)",
                    Boolean.class, a.id());
            int days = Boolean.TRUE.equals(hasHistory) ? REFETCH_DAYS : BACKFILL_DAYS;
            LocalDate today = LocalDate.now(props.zone());

            for (int d = 1; d <= days; d++) {
                LocalDate day = today.minusDays(d);
                try {
                    run.wrote(collectDay(a, day, run));
                } catch (IgApiException e) {
                    if (e.isAuthFailure()) throw e;
                    // One bad day shouldn't cost the others.
                    log.warn("@{} {}: {}", a.username(), day, e.getMessage());
                    run.partial();
                }
            }
            run.ok();
            log.info("@{}: account insights, {} days, {} values", a.username(), days, run.written());
            return run.written();

        } catch (IgApiException e) {
            if (e.isAuthFailure()) {
                accounts.deactivate(a.id());
            }
            run.failed(e.getCode(), e.getMessage());
            throw e;
        }
    }

    private int collectDay(Account a, LocalDate day, CollectorRuns.Run run) {
        Instant since = day.atStartOfDay(props.zone()).toInstant();
        Instant until = day.plusDays(1).atStartOfDay(props.zone()).toInstant();
        List<InsightRow> rows = new ArrayList<>();

        // Core metrics together, splitting around any Meta has retired.
        rows.addAll(api.tolerant(IgMetricCatalog.accountCoreMetrics(), metrics -> {
            run.call();
            return api.withRetry(() -> api.accountTotals(
                    a.loginType(), a.igUserId(), metrics, null, since, until, a.token()), 3);
        }, bad -> log.warn("@{}: account metric '{}' rejected by Meta - update IgMetricCatalog",
                a.username(), bad)));

        // Breakdowns: one metric, one breakdown, one request each.
        for (Breakdown b : IgMetricCatalog.ACCOUNT_BREAKDOWNS) {
            rows.addAll(optional(a, run, b.metric(), b.name(), since, until));
        }
        // Needs 100+ followers; fails quietly below that.
        rows.addAll(optional(a, run, "follows_and_unfollows", null, since, until));

        return upsert(a.id(), day, rows);
    }

    private List<InsightRow> optional(Account a, CollectorRuns.Run run, String metric,
                                      String breakdown, Instant since, Instant until) {
        try {
            run.call();
            JsonNode r = api.withRetry(() -> api.accountTotals(
                    a.loginType(), a.igUserId(), metric, breakdown, since, until, a.token()), 3);
            return api.parseInsights(r);
        } catch (IgApiException e) {
            if (e.isAuthFailure()) throw e;
            log.debug("@{}: optional {} / {} unavailable: {}", a.username(), metric, breakdown, e.getMessage());
            return List.of();
        }
    }

    private int upsert(long accountId, LocalDate day, List<InsightRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<Object[]> args = rows.stream().map(r -> new Object[]{
                accountId, Date.valueOf(day), r.metric(),
                nz(r.breakdown()), nz(r.dimension()).toUpperCase(), r.value()
        }).toList();

        jdbc.batchUpdate("""
                insert into account_metric_daily (account_id, stat_date, metric, breakdown, dimension, value)
                values (?, ?, ?, ?, ?, ?)
                on conflict (account_id, stat_date, metric, breakdown, dimension)
                do update set value = excluded.value, collected_at = now()
                """, args);
        return args.size();
    }

    /**
     * Profile counts, and today's follower snapshot. Meta reports followers as
     * a single current number with no history; this table is where the history
     * comes from, and it works below 100 followers where follows_and_unfollows
     * doesn't.
     */
    private void refreshProfile(Account a, CollectorRuns.Run run) {
        run.call();
        JsonNode me = api.withRetry(() -> api.me(a.loginType(), a.igUserId(), a.token()), 3);

        Integer followers = intOrNull(me, "followers_count");
        Integer follows = intOrNull(me, "follows_count");
        Integer media = intOrNull(me, "media_count");

        jdbc.update("""
                update ig_account set
                    username = coalesce(?, username), name = ?, account_type = coalesce(?, account_type),
                    biography = ?, website = ?, profile_picture_url = ?,
                    followers_count = ?, follows_count = ?, media_count = ?
                where id = ?
                """,
                textOrNull(me, "username"), textOrNull(me, "name"), textOrNull(me, "account_type"),
                textOrNull(me, "biography"), textOrNull(me, "website"), textOrNull(me, "profile_picture_url"),
                followers, follows, media, a.id());

        Date today = Date.valueOf(LocalDate.now(props.zone()));
        jdbc.update("""
                insert into account_daily_snapshot
                    (account_id, stat_date, followers_count, follows_count, media_count, follower_delta)
                values (?, ?, ?, ?, ?,
                        ? - (select followers_count from account_daily_snapshot
                             where account_id = ? and stat_date < ?
                             order by stat_date desc limit 1))
                on conflict (account_id, stat_date) do update set
                    followers_count = excluded.followers_count,
                    follows_count   = excluded.follows_count,
                    media_count     = excluded.media_count,
                    follower_delta  = excluded.follower_delta,
                    captured_at     = now()
                """, a.id(), today, followers, follows, media, followers, a.id(), today);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Integer intOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asInt();
    }

    private static String textOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }
}
