package com.rvmendillo.igstats.ig;

import com.rvmendillo.igstats.ig.IgMetricCatalog.LoginType;
import com.rvmendillo.igstats.ig.IgMetricCatalog.ProductType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog's whole job is to never ask for a metric a media type doesn't
 * carry - one bad metric fails the entire request with code 100.
 */
class IgMetricCatalogTest {

    private static List<String> metrics(ProductType type, LoginType login) {
        return Arrays.asList(IgMetricCatalog.mediaMetrics(type, login).split(","));
    }

    @Test
    void deadMetricsAreNeverRequested() {
        for (ProductType type : ProductType.values()) {
            for (LoginType login : LoginType.values()) {
                assertThat(metrics(type, login))
                        .as("%s via %s", type, login)
                        .doesNotContain("impressions", "plays", "video_views",
                                "clips_replays_count", "ig_reels_aggregated_all_plays_count");
            }
        }
    }

    @Test
    void storiesOnlyGetStoryMetrics() {
        assertThat(metrics(ProductType.STORY, LoginType.INSTAGRAM))
                .contains("views", "reach", "replies", "shares", "follows", "profile_visits")
                .doesNotContain("likes", "comments", "saved", "ig_reels_avg_watch_time");
    }

    @Test
    void breakdownMetricsAreFetchedSeparately() {
        // navigation and profile_activity carry breakdowns; mixed into a
        // multi-metric request they error, so they must not appear here.
        assertThat(metrics(ProductType.STORY, LoginType.FACEBOOK))
                .doesNotContain("navigation", "profile_activity");

        assertThat(IgMetricCatalog.breakdownRequests(ProductType.STORY))
                .extracting(IgMetricCatalog.Breakdown::metric)
                .contains("navigation", "profile_activity");
    }

    @Test
    void facebookOnlyMetricsStayOffInstagramLogin() {
        assertThat(metrics(ProductType.STORY, LoginType.INSTAGRAM)).doesNotContain("link_clicks", "total_views");
        assertThat(metrics(ProductType.STORY, LoginType.FACEBOOK)).contains("link_clicks");
        assertThat(metrics(ProductType.FEED, LoginType.INSTAGRAM)).doesNotContain("total_likes", "total_comments");
    }

    @Test
    void savesIsSingularOnMediaPluralOnAccount() {
        assertThat(metrics(ProductType.FEED, LoginType.INSTAGRAM)).contains("saved").doesNotContain("saves");
        assertThat(IgMetricCatalog.accountTotalValueMetrics().split(",")).contains("saves").doesNotContain("saved");
    }

    @Test
    void reelsGetWatchTimeButNotFeedOnlyMetrics() {
        assertThat(metrics(ProductType.REELS, LoginType.INSTAGRAM))
                .contains("ig_reels_avg_watch_time", "ig_reels_video_view_total_time", "reels_skip_rate")
                .doesNotContain("follows", "profile_visits", "replies");
    }

    @Test
    void timeSeriesExcludesTotalValueOnlyMetrics() {
        assertThat(IgMetricCatalog.accountTimeSeriesMetrics().split(","))
                .doesNotContain("follows_and_unfollows", "profile_links_taps")
                .contains("reach", "views");
    }

    @Test
    void unknownProductTypeFallsBackInsteadOfThrowing() {
        assertThat(IgMetricCatalog.productType("SOMETHING_META_ADDS_LATER")).isEqualTo(ProductType.FEED);
        assertThat(IgMetricCatalog.productType("story")).isEqualTo(ProductType.STORY);
    }
}
