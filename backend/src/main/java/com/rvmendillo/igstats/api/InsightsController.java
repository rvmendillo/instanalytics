package com.rvmendillo.igstats.api;

import com.rvmendillo.igstats.config.IgProperties;
import com.rvmendillo.igstats.ig.IgMetricCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Account, posts and audience. Everything here reads from your own database -
 * no request in this class touches Instagram.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class InsightsController {

    /** Sort keys the posts endpoint accepts, mapped to SQL. Never interpolate input. */
    private static final Map<String, String> MEDIA_SORT = Map.of(
            "views", "views",
            "reach", "reach",
            "engagement", "engagement_rate",
            "shares", "shares",
            "saved", "saved",
            "recent", "published_at");

    private final JdbcTemplate jdbc;
    private final IgProperties props;

    public InsightsController(JdbcTemplate jdbc, IgProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    // ------------------------------------------------------------------
    // Account
    // ------------------------------------------------------------------

    /**
     * Headline numbers for the window, each with the previous window of the
     * same length for comparison.
     *
     * <p>Reach and accounts engaged are unique-account counts, so summing daily
     * values would count the same follower once per day. They are reported as a
     * daily average instead; {@code aggregation} on each row says which.
     */
    @GetMapping("/account/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "1") long accountId,
                                        @RequestParam(defaultValue = "30") int days) {
        LocalDate today = LocalDate.now(props.zone());
        Date curFrom = Date.valueOf(today.minusDays(days));
        Date prevFrom = Date.valueOf(today.minusDays(2L * days));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", jdbc.queryForList("""
                select username, name, account_type, followers_count, follows_count, media_count,
                       profile_picture_url, last_synced_at
                from ig_account where id = ?
                """, accountId).stream().findFirst().orElse(Map.of()));

        String unique = "'" + String.join("','", IgMetricCatalog.UNIQUE_COUNT_METRICS) + "'";
        out.put("metrics", jdbc.queryForList("""
                with windowed as (
                    select metric, value,
                           case when stat_date >= ? then 'cur' else 'prev' end as w
                    from account_metric_daily
                    where account_id = ? and breakdown = '' and stat_date >= ? and stat_date < ?
                )
                select metric,
                       case when metric in (%s) then 'daily_avg' else 'sum' end as aggregation,
                       round(case when metric in (%s)
                                  then avg(value) filter (where w = 'cur')
                                  else sum(value) filter (where w = 'cur') end)  as current,
                       round(case when metric in (%s)
                                  then avg(value) filter (where w = 'prev')
                                  else sum(value) filter (where w = 'prev') end) as previous,
                       count(*) filter (where w = 'cur')                          as days_with_data
                from windowed
                group by metric
                order by metric
                """.formatted(unique, unique, unique),
                curFrom, accountId, prevFrom, Date.valueOf(today)));

        out.put("followers", jdbc.queryForMap("""
                select
                  (select followers_count from account_daily_snapshot
                    where account_id = ? order by stat_date desc limit 1)          as current,
                  (select followers_count from account_daily_snapshot
                    where account_id = ? and stat_date <= ?
                    order by stat_date desc limit 1)                                as at_window_start,
                  (select count(*) from account_daily_snapshot where account_id = ?) as days_tracked
                """, accountId, accountId, curFrom, accountId));

        out.put("days", days);
        return out;
    }

    /**
     * One metric as a daily series. {@code metric=followers} reads the follower
     * snapshots; anything else reads account_metric_daily.
     */
    @GetMapping("/account/series")
    public List<Map<String, Object>> series(@RequestParam(defaultValue = "1") long accountId,
                                            @RequestParam String metric,
                                            @RequestParam(defaultValue = "90") int days) {
        Date from = Date.valueOf(LocalDate.now(props.zone()).minusDays(days));
        if ("followers".equals(metric)) {
            return jdbc.queryForList("""
                    select stat_date as date, followers_count as value, follower_delta as delta
                    from account_daily_snapshot
                    where account_id = ? and stat_date >= ?
                    order by stat_date
                    """, accountId, from);
        }
        return jdbc.queryForList("""
                select stat_date as date, value
                from account_metric_daily
                where account_id = ? and metric = ? and breakdown = '' and stat_date >= ?
                order by stat_date
                """, accountId, metric, from);
    }

    /** Views split by follower status and by surface, summed over the window. */
    @GetMapping("/account/views-split")
    public List<Map<String, Object>> viewsSplit(@RequestParam(defaultValue = "1") long accountId,
                                                @RequestParam(defaultValue = "30") int days) {
        Date from = Date.valueOf(LocalDate.now(props.zone()).minusDays(days));
        return jdbc.queryForList("""
                select breakdown, dimension, sum(value) as value
                from account_metric_daily
                where account_id = ? and metric = 'views' and breakdown <> '' and stat_date >= ?
                group by breakdown, dimension
                order by breakdown, value desc
                """, accountId, from);
    }

    // ------------------------------------------------------------------
    // Posts and reels
    // ------------------------------------------------------------------

    /**
     * Posts with their latest insight snapshot. Engagement rate is
     * interactions / reach - the share of people who saw it and did something.
     */
    @GetMapping("/media")
    public List<Map<String, Object>> media(@RequestParam(defaultValue = "1") long accountId,
                                           @RequestParam(required = false) String type,
                                           @RequestParam(defaultValue = "views") String sort,
                                           @RequestParam(defaultValue = "50") int limit) {
        String orderBy = MEDIA_SORT.getOrDefault(sort, "views");
        return jdbc.queryForList("""
                select * from (
                    select m.id, m.ig_media_id, m.media_product_type, m.media_type, m.caption,
                           m.permalink, m.thumbnail_url, m.media_url, m.published_at,
                           m.like_count, m.comments_count,
                           l.views, l.reach, l.likes, l.comments, l.saved, l.shares, l.reposts,
                           l.follows, l.profile_visits, l.avg_watch_ms, l.skip_rate,
                           coalesce(l.total_interactions,
                                    coalesce(l.likes,0) + coalesce(l.comments,0)
                                  + coalesce(l.saved,0) + coalesce(l.shares,0)) as interactions,
                           round(100.0 * coalesce(l.total_interactions,
                                    coalesce(l.likes,0) + coalesce(l.comments,0)
                                  + coalesce(l.saved,0) + coalesce(l.shares,0))
                                 / nullif(l.reach, 0), 2)                         as engagement_rate,
                           l.captured_at                                           as insights_at
                    from media m
                    left join lateral (
                        select s.captured_at,
                               max(s.value) filter (where s.metric = 'views')                   as views,
                               max(s.value) filter (where s.metric = 'reach')                   as reach,
                               max(s.value) filter (where s.metric = 'likes')                   as likes,
                               max(s.value) filter (where s.metric = 'comments')                as comments,
                               max(s.value) filter (where s.metric = 'saved')                   as saved,
                               max(s.value) filter (where s.metric = 'shares')                  as shares,
                               max(s.value) filter (where s.metric = 'reposts')                 as reposts,
                               max(s.value) filter (where s.metric = 'follows')                 as follows,
                               max(s.value) filter (where s.metric = 'profile_visits')          as profile_visits,
                               max(s.value) filter (where s.metric = 'total_interactions')      as total_interactions,
                               max(s.value) filter (where s.metric = 'ig_reels_avg_watch_time') as avg_watch_ms,
                               max(s.value) filter (where s.metric = 'reels_skip_rate')         as skip_rate
                        from media_metric_snapshot s
                        where s.media_id = m.id and s.breakdown = ''
                          and s.captured_at = (select max(captured_at) from media_metric_snapshot
                                               where media_id = m.id)
                        group by s.captured_at
                    ) l on true
                    where m.account_id = ?
                      and (cast(? as text) is null or m.media_product_type = ?)
                ) ranked
                order by %s desc nulls last
                limit ?
                """.formatted(orderBy), accountId, type, type, limit);
    }

    /**
     * When your posts do best, by weekday and by time of day, in your zone.
     *
     * <p>Only posts older than 7 days count: a 2-day-old post hasn't finished
     * accruing reach, and including it would make whatever slot you posted in
     * most recently look worse than it is. Samples are returned alongside each
     * average, because three posts is an anecdote, not a pattern.
     */
    @GetMapping("/media/best-times")
    public Map<String, Object> bestTimes(@RequestParam(defaultValue = "1") long accountId,
                                         @RequestParam(defaultValue = "180") int days) {
        // The lateral join drops posts with no reach snapshot yet, so averages
        // only ever cover posts that have a number.
        String from = """
                from media m
                cross join lateral (
                    select s.value as reach from media_metric_snapshot s
                    where s.media_id = m.id and s.metric = 'reach' and s.breakdown = ''
                    order by s.captured_at desc
                    limit 1
                ) r
                where m.account_id = ?
                  and m.media_product_type in ('FEED', 'REELS')
                  and m.published_at <  now() - interval '7 days'
                  and m.published_at >= now() - make_interval(days => ?)
                group by 1
                order by 1
                """;
        String zone = props.zone().getId();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byWeekday", jdbc.queryForList(
                "select extract(isodow from m.published_at at time zone ?)::int as slot, "
                        + "count(*) as posts, round(avg(r.reach)) as avg_reach " + from,
                zone, accountId, days));
        out.put("byTimeOfDay", jdbc.queryForList(
                "select (floor(extract(hour from m.published_at at time zone ?) / 4) * 4)::int as slot, "
                        + "count(*) as posts, round(avg(r.reach)) as avg_reach " + from,
                zone, accountId, days));
        out.put("zone", zone);
        out.put("note", "Posts older than 7 days only. Weekday slots are ISO (1 = Monday); "
                + "time slots are 4-hour blocks starting at the hour shown.");
        return out;
    }

    // ------------------------------------------------------------------
    // Audience
    // ------------------------------------------------------------------

    /** Latest snapshot of one demographic breakdown, with each slice's share. */
    @GetMapping("/audience")
    public Map<String, Object> audience(@RequestParam(defaultValue = "1") long accountId,
                                        @RequestParam(defaultValue = "follower_demographics") String metric,
                                        @RequestParam(defaultValue = "age") String breakdown,
                                        @RequestParam(defaultValue = "12") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                with latest as (
                    select max(snapshot_on) as d from audience_demographic
                    where account_id = ? and metric = ? and breakdown = ?
                ), total as (
                    select sum(value) as t from audience_demographic a, latest
                    where a.account_id = ? and a.metric = ? and a.breakdown = ? and a.snapshot_on = latest.d
                )
                select a.dimension, a.value,
                       round(100.0 * a.value / nullif(total.t, 0), 1) as share,
                       a.snapshot_on
                from audience_demographic a, latest, total
                where a.account_id = ? and a.metric = ? and a.breakdown = ? and a.snapshot_on = latest.d
                order by a.value desc
                limit ?
                """, accountId, metric, breakdown, accountId, metric, breakdown,
                accountId, metric, breakdown, limit);
        out.put("metric", metric);
        out.put("breakdown", breakdown);
        out.put("rows", rows);
        out.put("snapshotOn", rows.isEmpty() ? null : rows.get(0).get("snapshot_on"));
        return out;
    }

    // ------------------------------------------------------------------
    // Health of the collectors
    // ------------------------------------------------------------------

    /** Recent job runs - the first place to look when numbers stop moving. */
    @GetMapping("/collector-runs")
    public List<Map<String, Object>> collectorRuns(@RequestParam(defaultValue = "1") long accountId,
                                                   @RequestParam(defaultValue = "25") int limit) {
        return jdbc.queryForList("""
                select collector, started_at, finished_at, status, items_written, api_calls,
                       error_code, error_message
                from collector_run
                where account_id = ?
                order by started_at desc
                limit ?
                """, accountId, limit);
    }
}
