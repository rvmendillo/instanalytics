package com.rvmendillo.igstats.collect;

import com.rvmendillo.igstats.auth.AccountStore;
import com.rvmendillo.igstats.auth.AccountStore.Account;
import com.rvmendillo.igstats.config.IgProperties;
import com.rvmendillo.igstats.ig.GraphApiClient;
import com.rvmendillo.igstats.ig.GraphApiClient.InsightRow;
import com.rvmendillo.igstats.ig.IgApiException;
import com.rvmendillo.igstats.ig.IgMetricCatalog;
import com.rvmendillo.igstats.ig.IgMetricCatalog.Metric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Audience composition: age, gender, city, country - for followers, for the
 * accounts you reached, and for the accounts that engaged.
 *
 * <p>Meta returns only the audience as it stands today. There is no history
 * endpoint, so this daily snapshot is the only way to ever see your audience
 * shift, and nobody can backfill it for you later.
 *
 * <p>Needs 100+ followers. Below that the API returns empty data rather than an
 * error; that is recorded as "nothing yet", not a failure.
 */
@Component
public class DemographicsCollector {

    private static final Logger log = LoggerFactory.getLogger(DemographicsCollector.class);
    private static final String NAME = "demographics";
    private static final String TIMEFRAME = "this_month";

    private final GraphApiClient api;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final IgProperties props;
    private final CollectorRuns runs;

    public DemographicsCollector(GraphApiClient api, AccountStore accounts, JdbcTemplate jdbc,
                                 IgProperties props, CollectorRuns runs) {
        this.api = api;
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.props = props;
        this.runs = runs;
    }

    @Scheduled(cron = "0 41 4 * * *", zone = "${ig.zone:Asia/Manila}")
    public void run() {
        for (Account a : accounts.active()) {
            try {
                collect(a);
            } catch (Exception e) {
                log.error("demographics failed for @{}", a.username(), e);
            }
        }
    }

    public int collect(Account a) {
        CollectorRuns.Run run = runs.start(a.id(), NAME);
        Date today = Date.valueOf(LocalDate.now(props.zone()));
        try {
            for (Metric metric : IgMetricCatalog.demographicMetrics()) {
                for (String breakdown : IgMetricCatalog.DEMOGRAPHIC_BREAKDOWNS) {
                    List<InsightRow> rows = fetch(a, metric.name(), breakdown, run);
                    for (InsightRow r : rows) {
                        jdbc.update("""
                                insert into audience_demographic
                                    (account_id, snapshot_on, metric, breakdown, dimension, value, timeframe)
                                values (?, ?, ?, ?, ?, ?, ?)
                                on conflict (account_id, snapshot_on, metric, breakdown, dimension)
                                do update set value = excluded.value, timeframe = excluded.timeframe
                                """, a.id(), today, metric.name(), breakdown, r.dimension(), r.value(), TIMEFRAME);
                    }
                    run.wrote(rows.size());
                }
            }
            if (run.written() == 0) {
                log.info("@{}: no demographics returned - Meta requires 100+ followers", a.username());
            }
            run.ok();
            return run.written();

        } catch (IgApiException e) {
            if (e.isAuthFailure()) {
                accounts.deactivate(a.id());
            }
            run.failed(e.getCode(), e.getMessage());
            throw e;
        }
    }

    /**
     * Try with a timeframe; if Meta rejects that combination, retry without.
     * The accepted timeframes differ by metric and have changed before.
     */
    private List<InsightRow> fetch(Account a, String metric, String breakdown, CollectorRuns.Run run) {
        try {
            run.call();
            return api.parseInsights(api.withRetry(() -> api.demographics(
                    a.loginType(), a.igUserId(), metric, breakdown, TIMEFRAME, a.token()), 3));
        } catch (IgApiException e) {
            if (e.isAuthFailure()) throw e;
            try {
                run.call();
                return api.parseInsights(api.withRetry(() -> api.demographics(
                        a.loginType(), a.igUserId(), metric, breakdown, null, a.token()), 3));
            } catch (IgApiException again) {
                if (again.isAuthFailure()) throw again;
                log.debug("@{}: {} by {} unavailable: {}", a.username(), metric, breakdown, again.getMessage());
                run.partial();
                return List.of();
            }
        }
    }
}
