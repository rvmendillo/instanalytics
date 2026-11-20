package com.rvmendillo.igstats.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.rvmendillo.igstats.auth.AccountStore;
import com.rvmendillo.igstats.auth.AccountStore.Account;
import com.rvmendillo.igstats.ig.GraphApiClient;
import com.rvmendillo.igstats.ig.GraphApiClient.InsightRow;
import com.rvmendillo.igstats.ig.IgApiException;
import com.rvmendillo.igstats.ig.IgMetricCatalog;
import com.rvmendillo.igstats.ig.IgMetricCatalog.Breakdown;
import com.rvmendillo.igstats.ig.IgMetricCatalog.ProductType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Posts and reels, with insight snapshots over time.
 *
 * <p>A post's numbers keep moving for weeks, so a single value is a point on a
 * curve, not a result. Posts under 30 days old are re-snapshotted every run;
 * the deltas between snapshots show which posts are still being pushed by the
 * algorithm. Older posts get one snapshot each, backfilled a batch at a time
 * so a first sync of a large account doesn't burn the hourly rate limit.
 */
@Component
public class MediaSyncCollector {

    private static final Logger log = LoggerFactory.getLogger(MediaSyncCollector.class);
    private static final String NAME = "media";
    private static final int PAGE_SIZE = 50;
    private static final int FULL_SYNC_PAGES = 40;     // up to 2,000 posts on first run
    private static final int INCREMENTAL_PAGES = 2;    // newest 100 afterwards
    private static final int BACKFILL_PER_RUN = 60;

    private final GraphApiClient api;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final CollectorRuns runs;

    /** Metrics Meta rejected, per product type - logged once, not every post. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    public MediaSyncCollector(GraphApiClient api, AccountStore accounts, JdbcTemplate jdbc, CollectorRuns runs) {
        this.api = api;
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.runs = runs;
    }

    /** Every 6 hours at :07. */
    @Scheduled(cron = "0 7 */6 * * *", zone = "${ig.zone:Asia/Manila}")
    public void run() {
        for (Account a : accounts.active()) {
            try {
                collect(a);
            } catch (Exception e) {
                log.error("media sync failed for @{}", a.username(), e);
            }
        }
    }

    public int collect(Account a) {
        CollectorRuns.Run run = runs.start(a.id(), NAME);
        try {
            Boolean any = jdbc.queryForObject(
                    "select exists(select 1 from media where account_id = ?)", Boolean.class, a.id());
            int pages = Boolean.TRUE.equals(any) ? INCREMENTAL_PAGES : FULL_SYNC_PAGES;

            int seen = syncList(a, pages, run);

            List<Target> targets = new ArrayList<>(jdbc.query("""
                    select id, ig_media_id, media_product_type from media
                    where account_id = ? and published_at >= now() - interval '30 days'
                    order by published_at desc
                    """, (rs, i) -> new Target(rs.getLong(1), rs.getString(2), rs.getString(3)), a.id()));

            targets.addAll(jdbc.query("""
                    select m.id, m.ig_media_id, m.media_product_type from media m
                    where m.account_id = ? and m.published_at < now() - interval '30 days'
                      and not exists (select 1 from media_metric_snapshot s where s.media_id = m.id)
                    order by m.published_at desc
                    limit ?
                    """, (rs, i) -> new Target(rs.getLong(1), rs.getString(2), rs.getString(3)),
                    a.id(), BACKFILL_PER_RUN));

            for (Target t : targets) {
                try {
                    run.wrote(snapshot(a, t, run));
                } catch (IgApiException e) {
                    if (e.isAuthFailure()) throw e;
                    log.warn("@{} media {}: {}", a.username(), t.igMediaId(), e.getMessage());
                    run.partial();
                }
            }

            run.ok();
            log.info("@{}: {} media listed, {} snapshotted, {} values",
                    a.username(), seen, targets.size(), run.written());
            return run.written();

        } catch (IgApiException e) {
            if (e.isAuthFailure()) {
                accounts.deactivate(a.id());
            }
            run.failed(e.getCode(), e.getMessage());
            throw e;
        }
    }

    private int syncList(Account a, int maxPages, CollectorRuns.Run run) {
        String after = null;
        int seen = 0;
        for (int p = 0; p < maxPages; p++) {
            final String cursor = after;
            run.call();
            JsonNode page = api.withRetry(() ->
                    api.media(a.loginType(), a.igUserId(), PAGE_SIZE, cursor, a.token()), 3);

            for (JsonNode item : page.path("data")) {
                upsertMedia(a.id(), item);
                seen++;
            }
            after = page.path("paging").path("cursors").path("after").asText(null);
            if (after == null || page.path("paging").path("next").isMissingNode()) {
                break;
            }
        }
        return seen;
    }

    private void upsertMedia(long accountId, JsonNode m) {
        jdbc.update("""
                insert into media (account_id, ig_media_id, media_product_type, media_type, caption,
                                   permalink, media_url, thumbnail_url, published_at,
                                   like_count, comments_count, is_shared_to_feed)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (account_id, ig_media_id) do update set
                    caption           = excluded.caption,
                    media_url         = excluded.media_url,
                    thumbnail_url     = excluded.thumbnail_url,
                    like_count        = excluded.like_count,
                    comments_count    = excluded.comments_count,
                    is_shared_to_feed = excluded.is_shared_to_feed
                """,
                accountId,
                m.path("id").asText(),
                m.path("media_product_type").asText("FEED"),
                m.path("media_type").asText(null),
                m.path("caption").asText(null),
                m.path("permalink").asText(null),
                m.path("media_url").asText(null),
                m.path("thumbnail_url").asText(null),
                Timestamp.from(StoryInsightsCollector.parseIgTime(m.path("timestamp").asText())),
                m.hasNonNull("like_count") ? m.get("like_count").asInt() : null,
                m.hasNonNull("comments_count") ? m.get("comments_count").asInt() : null,
                m.hasNonNull("is_shared_to_feed") ? m.get("is_shared_to_feed").asBoolean() : null);
    }

    private int snapshot(Account a, Target t, CollectorRuns.Run run) {
        ProductType type = IgMetricCatalog.productType(t.productType());
        List<InsightRow> rows = new ArrayList<>(api.tolerant(
                IgMetricCatalog.mediaMetricList(type, a.loginType()),
                metrics -> {
                    run.call();
                    return api.withRetry(() -> api.mediaInsights(
                            a.loginType(), t.igMediaId(), metrics, a.token()), 3);
                },
                bad -> {
                    if (reported.add(type + ":" + bad)) {
                        log.warn("Meta rejected '{}' for {} media - update IgMetricCatalog", bad, type);
                    }
                }));

        for (Breakdown b : IgMetricCatalog.breakdownRequests(type)) {
            try {
                run.call();
                rows.addAll(api.parseInsights(api.withRetry(() -> api.mediaInsightsBreakdown(
                        a.loginType(), t.igMediaId(), b.metric(), b.name(), a.token()), 3)));
            } catch (IgApiException e) {
                if (e.isAuthFailure()) throw e;
            }
        }

        // Crosspost metrics throw when the post wasn't shared to Facebook.
        // That's an answer ("not crossposted"), not a failure.
        String crosspost = IgMetricCatalog.crosspostMetrics(type);
        if (!crosspost.isEmpty()) {
            try {
                run.call();
                rows.addAll(api.parseInsights(api.mediaInsights(
                        a.loginType(), t.igMediaId(), crosspost, a.token())));
            } catch (IgApiException e) {
                if (e.isAuthFailure()) throw e;
            }
        }

        if (rows.isEmpty()) {
            return 0;
        }
        Timestamp at = Timestamp.from(Instant.now());
        List<Object[]> args = rows.stream().map(r -> new Object[]{
                t.id(), at, r.metric(),
                r.breakdown() == null ? "" : r.breakdown(),
                r.dimension() == null ? "" : r.dimension().toUpperCase(),
                r.value()
        }).toList();
        jdbc.batchUpdate("""
                insert into media_metric_snapshot (media_id, captured_at, metric, breakdown, dimension, value)
                values (?, ?, ?, ?, ?, ?)
                on conflict do nothing
                """, args);
        return args.size();
    }

    private record Target(long id, String igMediaId, String productType) {
    }
}
