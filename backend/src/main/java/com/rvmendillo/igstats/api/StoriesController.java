package com.rvmendillo.igstats.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The story archive - everything Instagram stopped showing you after 24 hours.
 *
 * <p>Derived metrics worth explaining, since none come from the API directly:
 * <ul>
 *   <li><b>exitRate</b> = (tap_exit + swipe_forward) / reach. The share of
 *       viewers who abandoned your stories entirely at this frame. The number
 *       that tells you which frame lost people.</li>
 *   <li><b>retention</b> = this frame's reach / reach of frame 1 that day.
 *       How many of your opening viewers were still watching. A multi-frame
 *       story that drops from 400 to 120 by frame 4 shows up here and nowhere
 *       in the app.</li>
 *   <li><b>rewatchRate</b> = tap_back / reach. People going back to look again -
 *       usually a sign the frame was dense or worth a second look.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/stories")
@CrossOrigin
public class StoriesController {

    private final JdbcTemplate jdbc;

    public StoriesController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Archive, newest first, with derived rates per frame. */
    @GetMapping
    public List<Map<String, Object>> archive(@RequestParam(defaultValue = "1") long accountId,
                                             @RequestParam(defaultValue = "100") int limit) {
        return jdbc.queryForList("""
                with day_first as (
                    select account_id, story_day,
                           max(reach) filter (where frame_index = 1) as first_reach
                    from story_frame
                    where account_id = ?
                    group by account_id, story_day
                )
                select f.id, f.ig_media_id, f.media_type, f.permalink,
                       f.published_at, f.expires_at, f.story_day, f.frame_index, f.frames_that_day,
                       f.views, f.reach, f.replies, f.shares, f.reposts,
                       f.total_interactions, f.follows, f.profile_visits, f.link_clicks,
                       f.nav_tap_forward, f.nav_tap_back, f.nav_swipe_forward, f.nav_tap_exit,
                       f.suppressed_low_volume, f.capture_count, f.complete,
                       round(100.0 * (coalesce(f.nav_tap_exit,0) + coalesce(f.nav_swipe_forward,0))
                             / nullif(f.reach, 0), 1) as exit_rate,
                       round(100.0 * coalesce(f.nav_tap_back,0) / nullif(f.reach, 0), 1) as rewatch_rate,
                       round(100.0 * f.reach / nullif(df.first_reach, 0), 1) as retention
                from story_frame f
                left join day_first df
                  on df.account_id = f.account_id
                 and df.story_day = f.story_day
                where f.account_id = ?
                order by f.published_at desc
                limit ?
                """, accountId, accountId, limit);
    }

    /** How one story's numbers accumulated, capture by capture. */
    @GetMapping("/{frameId}/timeline")
    public List<Map<String, Object>> timeline(@PathVariable long frameId) {
        return jdbc.queryForList("""
                select captured_at, metric, dimension, value
                from story_metric
                where story_frame_id = ?
                order by captured_at, metric, dimension
                """, frameId);
    }

    /** Aggregate behaviour over a window - the summary card. */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "1") long accountId,
                                       @RequestParam(defaultValue = "30") int days) {
        return jdbc.queryForMap("""
                select count(*)                                    as frames,
                       count(distinct story_day)                    as story_days,
                       coalesce(sum(views), 0)                      as total_views,
                       round(avg(reach))                            as avg_reach,
                       coalesce(sum(replies), 0)                    as total_replies,
                       coalesce(sum(follows), 0)                    as follows_from_stories,
                       coalesce(sum(link_clicks), 0)                as link_clicks,
                       round(100.0 * sum(coalesce(nav_tap_exit,0) + coalesce(nav_swipe_forward,0))
                             / nullif(sum(reach), 0), 1)            as avg_exit_rate,
                       round(100.0 * sum(coalesce(nav_tap_back,0))
                             / nullif(sum(reach), 0), 1)            as avg_rewatch_rate,
                       count(*) filter (where suppressed_low_volume) as under_five_viewers,
                       count(*) filter (where not complete
                                        and expires_at < now())      as missed
                from story_frame
                where account_id = ?
                  and published_at >= now() - make_interval(days => ?)
                """, accountId, days);
    }

    /**
     * Retention by frame position, averaged across days. Answers "how many frames
     * should my stories be?" - if retention falls off a cliff after frame 3, that
     * is your answer.
     */
    @GetMapping("/retention-curve")
    public List<Map<String, Object>> retentionCurve(@RequestParam(defaultValue = "1") long accountId,
                                                    @RequestParam(defaultValue = "90") int days) {
        return jdbc.queryForList("""
                with opener as (
                    select story_day, reach as r1
                    from story_frame
                    where account_id = ? and frame_index = 1 and reach > 0
                      and published_at >= now() - make_interval(days => ?)
                )
                select f.frame_index                                 as position,
                       count(*)                                      as samples,
                       round(avg(100.0 * f.reach / opener.r1), 1)    as avg_retention_pct
                from story_frame f
                join opener on opener.story_day = f.story_day
                where f.account_id = ? and f.reach is not null
                group by f.frame_index
                order by f.frame_index
                """, accountId, days, accountId);
    }
}
