package com.rvmendillo.igstats.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.rvmendillo.igstats.auth.AccountStore;
import com.rvmendillo.igstats.auth.AccountStore.Account;
import com.rvmendillo.igstats.config.IgProperties;
import com.rvmendillo.igstats.ig.GraphApiClient;
import com.rvmendillo.igstats.ig.GraphApiClient.InsightRow;
import com.rvmendillo.igstats.ig.IgApiException;
import com.rvmendillo.igstats.ig.IgMetricCatalog;
import com.rvmendillo.igstats.ig.IgMetricCatalog.ProductType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures story insights before Instagram deletes them.
 *
 * <p>This is the fix for "stories archive counts". Story insights exist on
 * Meta's side for 24 hours and then they are gone - not hidden, gone, at every
 * access tier. The in-app archive keeps the media but not the numbers, which is
 * why counts on old stories look wrong or empty. The only way to have them later
 * is to take them now, so this runs on a short interval and writes every capture.
 *
 * <p>Each poll:
 * <ol>
 *   <li>Lists live stories ({@code /stories} only ever returns unexpired ones).</li>
 *   <li>Upserts a {@code story_frame} per story and orders that day's frames,
 *       so drop-off across a multi-frame story can be reconstructed.</li>
 *   <li>Fetches scalar insights with a metric string built for STORY media.</li>
 *   <li>Fetches {@code navigation} separately with its breakdown - a breakdown
 *       request must stand alone or the whole call fails.</li>
 *   <li>Appends every value to {@code story_metric} and refreshes the frame's
 *       latest-value columns.</li>
 * </ol>
 *
 * <p>Failure handling is per story, not per run. A code 10 on one quiet frame
 * must not cost you the capture of the four frames that did get views.
 */
@Component
public class StoryInsightsCollector {

    private static final Logger log = LoggerFactory.getLogger(StoryInsightsCollector.class);
    private static final String NAME = "stories";
    private static final Duration STORY_LIFETIME = Duration.ofHours(24);

    /** Instagram sends offsets as +0000, which ISO_OFFSET_DATE_TIME rejects. */
    private static final DateTimeFormatter IG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private final GraphApiClient api;
    private final AccountStore accounts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final IgProperties props;

    public StoryInsightsCollector(GraphApiClient api, AccountStore accounts, JdbcTemplate jdbc,
                                  TransactionTemplate tx, IgProperties props) {
        this.api = api;
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.tx = tx;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${ig.story-poll-every:PT30M}", initialDelayString = "PT20S")
    public void run() {
        for (Account account : accounts.active()) {
            try {
                collect(account);
            } catch (Exception e) {
                // One account failing must never stop the others.
                log.error("story collection failed for @{}", account.username(), e);
            }
        }
    }

    public RunResult collect(Account account) {
        long runId = startRun(account.id());
        int calls = 0;
        int written = 0;
        int lowVolume = 0;

        try {
            JsonNode live = api.withRetry(
                    () -> api.stories(account.loginType(), account.igUserId(), account.token()), 3);
            calls++;

            List<JsonNode> stories = new ArrayList<>();
            live.path("data").forEach(stories::add);
            if (stories.isEmpty()) {
                finishRun(runId, "OK", 0, calls, null, null);
                return new RunResult(0, 0, 0);
            }

            Map<String, Long> frameIds = upsertFrames(account, stories);

            for (JsonNode story : stories) {
                String mediaId = story.path("id").asText();
                Long frameId = frameIds.get(mediaId);
                if (frameId == null) continue;

                Instant capturedAt = Instant.now();
                List<InsightRow> rows = new ArrayList<>();

                // 1. Scalar metrics, filtered to what STORY media carries.
                try {
                    String metrics = IgMetricCatalog.mediaMetrics(ProductType.STORY, account.loginType());
                    JsonNode resp = api.withRetry(() ->
                            api.mediaInsights(account.loginType(), mediaId, metrics, account.token()), 3);
                    calls++;
                    rows.addAll(api.parseInsights(resp));
                } catch (IgApiException e) {
                    calls++;
                    if (e.isAuthFailure()) throw e;
                    if (e.isLowVolume()) {
                        markLowVolume(frameId);
                        lowVolume++;
                        continue; // navigation will fail the same way; skip it
                    }
                    if (e.isUnsupportedMetric()) {
                        // A catalog bug: Meta changed what STORY carries. Loud,
                        // because it will silently drop data until fixed.
                        log.error("Unsupported story metric for {} - IgMetricCatalog needs updating: {}",
                                mediaId, e.getMessage());
                    } else {
                        log.warn("story {} scalar insights failed: {}", mediaId, e.getMessage());
                    }
                }

                // 2. Navigation with breakdown - must be its own request.
                try {
                    JsonNode nav = api.withRetry(() -> api.mediaInsightsBreakdown(
                            account.loginType(), mediaId, "navigation",
                            "story_navigation_action_type", account.token()), 3);
                    calls++;
                    rows.addAll(api.parseInsights(nav));
                } catch (IgApiException e) {
                    calls++;
                    if (e.isAuthFailure()) throw e;
                    if (!e.isLowVolume()) {
                        log.warn("story {} navigation failed: {}", mediaId, e.getMessage());
                    }
                }

                if (!rows.isEmpty()) {
                    written += persist(frameId, capturedAt, rows);
                }
            }

            String status = lowVolume > 0 ? "PARTIAL" : "OK";
            finishRun(runId, status, written, calls, null, null);
            accounts.touchSynced(account.id());
            log.info("@{}: {} live stories, {} values written, {} under the 5-viewer floor",
                    account.username(), stories.size(), written, lowVolume);
            return new RunResult(stories.size(), written, lowVolume);

        } catch (IgApiException e) {
            if (e.isAuthFailure()) {
                // Nothing further will work. Deactivate so we stop burning
                // quota, and surface it - the user has to reconnect.
                accounts.deactivate(account.id());
                log.error("@{}: token rejected (code 190). Account paused - reconnect required.",
                        account.username());
            }
            finishRun(runId, "FAILED", written, calls, e.getCode(), e.getMessage());
            throw e;
        }
    }

    // ------------------------------------------------------------------

    /**
     * Upsert frames and assign frame_index by publish order within each local
     * day. Re-numbered every poll, so a frame posted later in the day slots in
     * correctly even if an earlier poll already saw the others.
     */
    private Map<String, Long> upsertFrames(Account account, List<JsonNode> stories) {
        Map<String, Long> ids = new HashMap<>();

        tx.executeWithoutResult(status -> {
            java.util.Set<LocalDate> days = new java.util.LinkedHashSet<>();
            for (JsonNode s : stories) {
                Instant published = parseIgTime(s.path("timestamp").asText());
                LocalDate storyDay = published.atZone(props.zone()).toLocalDate();
                days.add(storyDay);
                Long id = jdbc.queryForObject("""
                        insert into story_frame (account_id, ig_media_id, media_type, permalink,
                                                 media_url, published_at, expires_at, story_day)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (account_id, ig_media_id) do update set
                            media_url = coalesce(excluded.media_url, story_frame.media_url),
                            permalink = coalesce(excluded.permalink, story_frame.permalink)
                        returning id
                        """, Long.class,
                        account.id(),
                        s.path("id").asText(),
                        s.path("media_type").asText(null),
                        s.path("permalink").asText(null),
                        s.path("media_url").asText(null),
                        Timestamp.from(published),
                        Timestamp.from(published.plus(STORY_LIFETIME)),
                        java.sql.Date.valueOf(storyDay));
                ids.put(s.path("id").asText(), id);
            }

            // Re-sequence every day touched by this poll, so a frame posted
            // later slots in after frames an earlier poll already numbered.
            for (LocalDate day : days) {
                jdbc.update("""
                        with ordered as (
                            select id,
                                   row_number() over (order by published_at) as idx,
                                   count(*) over () as total
                            from story_frame
                            where account_id = ? and story_day = ?
                        )
                        update story_frame f
                        set frame_index = o.idx, frames_that_day = o.total
                        from ordered o where f.id = o.id
                        """, account.id(), java.sql.Date.valueOf(day));
            }
        });
        return ids;
    }

    private int persist(long frameId, Instant capturedAt, List<InsightRow> rows) {
        Map<String, Long> latest = new HashMap<>();

        tx.executeWithoutResult(status -> {
            for (InsightRow r : rows) {
                String dim = r.dimension() == null ? "" : r.dimension().toUpperCase();
                jdbc.update("""
                        insert into story_metric (story_frame_id, captured_at, metric, dimension, value)
                        values (?, ?, ?, ?, ?)
                        on conflict do nothing
                        """, frameId, Timestamp.from(capturedAt), r.metric(), dim, r.value());

                // Navigation breakdown values map onto their own columns.
                String key = r.metric().equals("navigation") && !dim.isEmpty()
                        ? "nav_" + dim.toLowerCase()
                        : r.metric();
                latest.put(key, r.value());
            }

            jdbc.update("""
                    update story_frame set
                        views              = coalesce(?, views),
                        reach              = coalesce(?, reach),
                        replies            = coalesce(?, replies),
                        shares             = coalesce(?, shares),
                        reposts            = coalesce(?, reposts),
                        total_interactions = coalesce(?, total_interactions),
                        follows            = coalesce(?, follows),
                        profile_visits     = coalesce(?, profile_visits),
                        link_clicks        = coalesce(?, link_clicks),
                        nav_tap_forward    = coalesce(?, nav_tap_forward),
                        nav_tap_back       = coalesce(?, nav_tap_back),
                        nav_swipe_forward  = coalesce(?, nav_swipe_forward),
                        nav_tap_exit       = coalesce(?, nav_tap_exit),
                        capture_count      = capture_count + 1,
                        first_captured_at  = coalesce(first_captured_at, ?),
                        last_captured_at   = ?,
                        complete           = true
                    where id = ?
                    """,
                    latest.get("views"), latest.get("reach"), latest.get("replies"),
                    latest.get("shares"), latest.get("reposts"), latest.get("total_interactions"),
                    latest.get("follows"), latest.get("profile_visits"), latest.get("link_clicks"),
                    latest.get("nav_tap_forward"), latest.get("nav_tap_back"),
                    latest.get("nav_swipe_forward"), latest.get("nav_tap_exit"),
                    Timestamp.from(capturedAt), Timestamp.from(capturedAt), frameId);
        });
        return rows.size();
    }

    private void markLowVolume(long frameId) {
        jdbc.update("""
                update story_frame set suppressed_low_volume = true,
                                       capture_count = capture_count + 1,
                                       last_captured_at = now(),
                                       complete = true
                where id = ?
                """, frameId);
    }

    private long startRun(long accountId) {
        return jdbc.queryForObject("""
                insert into collector_run (account_id, collector) values (?, ?) returning id
                """, Long.class, accountId, NAME);
    }

    private void finishRun(long runId, String status, int written, int calls,
                           Integer errCode, String errMsg) {
        jdbc.update("""
                update collector_run set finished_at = now(), status = ?, items_written = ?,
                       api_calls = ?, error_code = ?, error_message = ?
                where id = ?
                """, status, written, calls, errCode, errMsg, runId);
    }

    static Instant parseIgTime(String raw) {
        return OffsetDateTime.parse(raw, IG_TIME).toInstant();
    }

    public record RunResult(int liveStories, int valuesWritten, int lowVolume) {
    }

    /** Frames by publish order, for the archive view. */
    static Comparator<JsonNode> byPublished() {
        return Comparator.comparing(n -> n.path("timestamp").asText());
    }
}
