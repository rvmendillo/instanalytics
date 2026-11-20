package com.rvmendillo.igstats.ig;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every insight metric the Instagram Platform API exposes, as of November 2026.
 *
 * <p>This exists because the API punishes guesswork. Ask for a metric the media
 * type does not carry and the whole request fails with code 100 - you do not get
 * partial results, you get nothing. So the metric string is always built from the
 * media's {@code media_product_type} rather than hardcoded, and never from a
 * remembered list.
 *
 * <p>Traps encoded here, each of which costs an afternoon to discover:
 * <ul>
 *   <li>{@code impressions} is <b>dead</b>. Deprecated for media created after
 *       2024-07-02 and removed across all API versions on 2025-04-21. {@code views}
 *       replaced it (and {@code plays}). Any tutorial still using it predates that.</li>
 *   <li>Saves is {@code saved} at media level but {@code saves} at account level.
 *       Singular vs plural, same concept. Not a typo here.</li>
 *   <li>A breakdown can only be requested alongside the one metric that supports
 *       it. Mixing a breakdown into a multi-metric request errors out, so
 *       breakdown metrics get their own call.</li>
 *   <li>Story insights live for 24 hours only, and return code 10 rather than a
 *       number when fewer than 5 people saw the story.</li>
 *   <li>{@code replies} on stories always returns 0 for accounts in Europe and
 *       Japan. Not a bug, a regional restriction.</li>
 *   <li>{@code facebook_views} and {@code crossposted_views} throw when the media
 *       wasn't shared to Facebook, taking the whole request down with them.
 *       They live in their own list and are never requested by default.</li>
 * </ul>
 */
public final class IgMetricCatalog {

    private IgMetricCatalog() {
    }

    /** Which OAuth path the account was connected through. Decides reachable metrics. */
    public enum LoginType {
        /** graph.instagram.com. Simpler to set up, no Facebook Page needed. */
        INSTAGRAM,
        /** graph.facebook.com. Unlocks Business Discovery, total_* and story link_clicks. */
        FACEBOOK
    }

    public enum ProductType { FEED, REELS, STORY }

    // ------------------------------------------------------------------
    // Media-level metrics
    // ------------------------------------------------------------------

    /** Available to both login paths. */
    private static final List<Metric> MEDIA_METRICS = List.of(
            Metric.of("views",            "Times the media was seen, repeats included. Successor to impressions and plays.",
                    EnumSet.allOf(ProductType.class)),
            Metric.of("reach",            "Unique accounts that saw it. Estimated by Meta.",
                    EnumSet.allOf(ProductType.class)),
            Metric.of("total_interactions", "Likes + comments + saves + shares, organic only.",
                    EnumSet.allOf(ProductType.class)),
            Metric.of("likes",            "Likes on this media only, excluding any boosted copy.",
                    EnumSet.of(ProductType.FEED, ProductType.REELS)),
            Metric.of("comments",         "Comments on this media only.",
                    EnumSet.of(ProductType.FEED, ProductType.REELS)),
            Metric.of("saved",            "Saves. Note the spelling - plural at account level.",
                    EnumSet.of(ProductType.FEED, ProductType.REELS)),
            Metric.of("shares",           "Shares, including to stories and DMs.",
                    EnumSet.allOf(ProductType.class)),
            Metric.of("reposts",          "Reposts. Added 2025-12-03.",
                    EnumSet.allOf(ProductType.class)),
            Metric.of("follows",          "Accounts that followed you from this media.",
                    EnumSet.of(ProductType.FEED, ProductType.STORY)),
            Metric.of("profile_visits",   "Profile visits driven by this media.",
                    EnumSet.of(ProductType.FEED, ProductType.STORY)),
            Metric.of("profile_activity", "Actions taken on your profile after viewing. Breakdown: action_type.",
                    EnumSet.of(ProductType.FEED, ProductType.STORY)),
            Metric.of("replies",          "Story replies. Always 0 for Europe and Japan.",
                    EnumSet.of(ProductType.STORY)),
            Metric.of("navigation",       "Total moves made during the story. Breakdown: story_navigation_action_type.",
                    EnumSet.of(ProductType.STORY)),
            Metric.of("ig_reels_avg_watch_time",       "Mean play time, milliseconds.",
                    EnumSet.of(ProductType.REELS)),
            Metric.of("ig_reels_video_view_total_time","Total play time including replays, milliseconds.",
                    EnumSet.of(ProductType.REELS)),
            Metric.of("reels_skip_rate",  "Skip rate. Estimated, still in development - expect gaps.",
                    EnumSet.of(ProductType.REELS))
    );

    /**
     * Metrics that <b>throw</b> when the media was not shared to Facebook. One of
     * these in a normal request fails the whole request, so they are never part
     * of {@link #mediaMetrics}; request them on their own and treat failure as
     * "not crossposted".
     */
    private static final List<Metric> CROSSPOST_METRICS = List.of(
            Metric.of("crossposted_views", "Views across Instagram and Facebook combined.",
                    EnumSet.of(ProductType.REELS)),
            Metric.of("facebook_views", "Facebook plays only.",
                    EnumSet.allOf(ProductType.class))
    );

    /**
     * Facebook Login only. These are the aggregated figures that fold in boosted,
     * promoted and ad copies of the media - the organic metrics above deliberately
     * exclude that traffic, which is why a promoted post's numbers never reconcile
     * with what you see in Ads Manager.
     */
    private static final List<Metric> MEDIA_METRICS_FB_ONLY = List.of(
            Metric.of("total_likes",    "Likes across all surfaces including ads. Added 2026-04-22.",
                    EnumSet.of(ProductType.FEED, ProductType.REELS)),
            Metric.of("total_comments", "Comments across all surfaces including ads. Added 2026-04-22.",
                    EnumSet.of(ProductType.FEED, ProductType.REELS)),
            Metric.of("total_views",    "Views across all surfaces including ads. Video media only.",
                    EnumSet.allOf(ProductType.class)),
            Metric.of("link_clicks",    "Taps on story link stickers. Added 2026-06-22.",
                    EnumSet.of(ProductType.STORY))
    );

    // ------------------------------------------------------------------
    // Account-level metrics
    // ------------------------------------------------------------------

    /**
     * Account metrics. Require {@code metric_type}: {@code total_value} for a single
     * total (breakdowns permitted) or {@code time_series} for per-day values
     * (breakdowns not permitted). Omitting since/until makes the API look back only
     * 24 hours, which is why a first request often looks empty.
     */
    private static final List<Metric> ACCOUNT_METRICS = List.of(
            Metric.account("views",              "Total content views. Breakdowns: follow_type, media_product_type."),
            Metric.account("reach",              "Unique accounts reached. Estimated."),
            Metric.account("accounts_engaged",   "Accounts that interacted at all. Estimated."),
            Metric.account("total_interactions", "All interactions summed."),
            Metric.account("likes",              "Likes received across content."),
            Metric.account("comments",           "Comments received."),
            Metric.account("saves",              "Saves. Plural here, singular at media level."),
            Metric.account("shares",             "Shares received."),
            Metric.account("replies",            "Story replies received."),
            Metric.account("reposts",            "Reposts received. Added 2025-12-03."),
            Metric.account("follows_and_unfollows", "Follows and unfollows. Needs 100+ followers."),
            Metric.account("profile_links_taps", "Taps on profile contact buttons. Breakdown: contact_button_type.")
    );

    /**
     * Demographics. These need {@code period=lifetime} plus a {@code timeframe}, and
     * silently return nothing below 100 followers. Meta gives you only the current
     * composition with no history, so snapshotting them daily is the only way to
     * ever see your audience shift - no third-party tool can backfill it for you.
     */
    private static final List<Metric> DEMOGRAPHIC_METRICS = List.of(
            Metric.account("follower_demographics",         "Current followers by age, city, country, gender."),
            Metric.account("engaged_audience_demographics", "Accounts that engaged, same breakdowns."),
            Metric.account("reached_audience_demographics", "Accounts reached, same breakdowns.")
    );

    /** Breakdown dimensions for demographic metrics. One breakdown per request. */
    public static final List<String> DEMOGRAPHIC_BREAKDOWNS = List.of("age", "city", "country", "gender");

    /**
     * Valid timeframes. {@code last_14_days}, {@code last_30_days}, {@code last_90_days}
     * and {@code prev_month} were withdrawn for the engaged/reached variants on
     * 2024-05-21 and now error; they remain valid for follower_demographics.
     */
    public static final List<String> SAFE_TIMEFRAMES = List.of("this_week", "this_month");

    // ------------------------------------------------------------------
    // Breakdowns
    // ------------------------------------------------------------------

    /**
     * Breakdown name -> the single metric it may accompany.
     *
     * <p>The navigation and action_type values are from Meta's media insights
     * reference. The follow_type, media_product_type and contact_button_type
     * values are as observed - the account insights reference doesn't enumerate
     * them - so they are used descriptively only, never to validate a response.
     */
    public static final List<Breakdown> MEDIA_BREAKDOWNS = List.of(
            new Breakdown("story_navigation_action_type", "navigation",
                    List.of("TAP_FORWARD", "TAP_BACK", "SWIPE_FORWARD", "TAP_EXIT")),
            new Breakdown("action_type", "profile_activity",
                    List.of("BIO_LINK_CLICKED", "CALL", "DIRECTION", "EMAIL", "TEXT", "OTHER"))
    );

    /**
     * Account-level only. Kept apart from {@link #MEDIA_BREAKDOWNS} on purpose:
     * two of these attach to {@code views}, and mixing the lists once caused
     * {@code views} to be stripped from every media request as if it were a
     * breakdown-only metric.
     */
    public static final List<Breakdown> ACCOUNT_BREAKDOWNS = List.of(
            new Breakdown("follow_type", "views",
                    List.of("FOLLOWER", "NON_FOLLOWER")),
            new Breakdown("media_product_type", "views",
                    List.of("AD", "FEED", "REELS", "STORY")),
            new Breakdown("contact_button_type", "profile_links_taps",
                    List.of("BIO_LINK_CLICKED", "CALL", "DIRECTION", "EMAIL", "INSTANT_EXPERIENCE", "OTHER", "TEXT"))
    );

    public static final List<Breakdown> BREAKDOWNS = java.util.stream.Stream.concat(
            MEDIA_BREAKDOWNS.stream(), ACCOUNT_BREAKDOWNS.stream()).toList();

    /**
     * Story navigation, decoded. The names are unintuitive enough to be worth
     * stating plainly, because three of the four are signals you want to go
     * <i>down</i> and the API does not tell you which:
     * <ul>
     *   <li>{@code TAP_FORWARD} - tapped to your next frame. Mild impatience.</li>
     *   <li>{@code TAP_BACK} - tapped back to rewatch. Usually a good sign.</li>
     *   <li>{@code SWIPE_FORWARD} - swiped away to the next <i>account</i>. They left.</li>
     *   <li>{@code TAP_EXIT} - closed stories entirely. They really left.</li>
     * </ul>
     * Summed, they equal the {@code navigation} total.
     */
    public static final List<String> NAVIGATION_ACTIONS =
            List.of("TAP_FORWARD", "TAP_BACK", "SWIPE_FORWARD", "TAP_EXIT");

    // ------------------------------------------------------------------
    // Query building
    // ------------------------------------------------------------------

    /**
     * Metric string for a media object, filtered to what this product type and
     * login path actually support. Breakdown-only metrics are excluded - request
     * those separately via {@link #breakdownRequests(ProductType)}.
     */
    public static String mediaMetrics(ProductType type, LoginType login) {
        Set<String> breakdownOwned = MEDIA_BREAKDOWNS.stream()
                .map(Breakdown::metric)
                .collect(Collectors.toSet());

        Set<String> out = new LinkedHashSet<>();
        for (Metric m : allMediaMetrics(login)) {
            if (m.appliesTo(type) && !breakdownOwned.contains(m.name())) {
                out.add(m.name());
            }
        }
        return String.join(",", out);
    }

    /**
     * Metrics that must each be fetched in their own request because they carry a
     * breakdown. Returns (metric, breakdown) pairs valid for this product type.
     */
    public static List<Breakdown> breakdownRequests(ProductType type) {
        Set<String> supported = allMediaMetrics(LoginType.FACEBOOK).stream()
                .filter(m -> m.appliesTo(type))
                .map(Metric::name)
                .collect(Collectors.toSet());

        return MEDIA_BREAKDOWNS.stream()
                .filter(b -> supported.contains(b.metric()))
                .toList();
    }

    /**
     * Crosspost metrics for this product type, as a metric string. Request on
     * its own; an error means the media simply wasn't shared to Facebook.
     */
    public static String crosspostMetrics(ProductType type) {
        return CROSSPOST_METRICS.stream()
                .filter(m -> m.appliesTo(type))
                .map(Metric::name)
                .collect(Collectors.joining(","));
    }

    /** Account metrics suitable for a {@code metric_type=time_series} request. */
    public static String accountTimeSeriesMetrics() {
        // follows_and_unfollows and profile_links_taps are total_value-only in
        // practice; requesting them as a time series errors on some accounts.
        Set<String> exclude = Set.of("follows_and_unfollows", "profile_links_taps");
        return ACCOUNT_METRICS.stream()
                .map(Metric::name)
                .filter(n -> !exclude.contains(n))
                .collect(Collectors.joining(","));
    }

    /** Account metrics for a {@code metric_type=total_value} request. */
    public static String accountTotalValueMetrics() {
        return ACCOUNT_METRICS.stream().map(Metric::name).collect(Collectors.joining(","));
    }

    public static List<Metric> demographicMetrics() {
        return DEMOGRAPHIC_METRICS;
    }

    public static List<Metric> allMediaMetrics(LoginType login) {
        if (login == LoginType.FACEBOOK) {
            return java.util.stream.Stream.concat(
                    MEDIA_METRICS.stream(), MEDIA_METRICS_FB_ONLY.stream()).toList();
        }
        return MEDIA_METRICS;
    }

    /** Fields worth requesting on the media edge itself, separate from insights. */
    public static final String MEDIA_FIELDS = String.join(",",
            "id", "caption", "media_type", "media_product_type", "media_url",
            "permalink", "thumbnail_url", "timestamp", "like_count",
            "comments_count", "is_shared_to_feed", "alt_text");

    /** Fields on your own account. */
    public static final String ACCOUNT_FIELDS = String.join(",",
            "id", "username", "name", "account_type", "biography", "website",
            "profile_picture_url", "followers_count", "follows_count", "media_count");

    /**
     * Business Discovery field set - the only sanctioned way to read another
     * account, and it works on Business/Creator accounts only. Facebook Login
     * path only. Personal accounts are simply not reachable, by design.
     */
    public static final String DISCOVERY_FIELDS = String.join(",",
            "username", "name", "biography", "website", "profile_picture_url",
            "followers_count", "follows_count", "media_count");

    public static final String DISCOVERY_MEDIA_FIELDS = String.join(",",
            "id", "caption", "media_type", "media_product_type", "permalink",
            "timestamp", "like_count", "comments_count");

    /** Human-readable catalog, surfaced in the dashboard's "what is tracked" view. */
    public static List<Metric> describeAll(LoginType login) {
        return java.util.stream.Stream.of(
                        allMediaMetrics(login).stream(),
                        CROSSPOST_METRICS.stream(),
                        ACCOUNT_METRICS.stream(),
                        DEMOGRAPHIC_METRICS.stream())
                .flatMap(s -> s)
                .toList();
    }

    // ------------------------------------------------------------------

    public record Metric(String name, String description, Set<ProductType> productTypes, boolean accountLevel) {
        static Metric of(String name, String description, Set<ProductType> types) {
            return new Metric(name, description, types, false);
        }

        static Metric account(String name, String description) {
            return new Metric(name, description, EnumSet.noneOf(ProductType.class), true);
        }

        boolean appliesTo(ProductType type) {
            return productTypes.contains(type);
        }
    }

    public record Breakdown(String name, String metric, List<String> values) {
        public boolean hasValue(String v) {
            return values.stream().anyMatch(x -> x.equalsIgnoreCase(v));
        }
    }

    /** Parse a product type defensively - Meta has added new ones without notice. */
    public static ProductType productType(String raw) {
        return Arrays.stream(ProductType.values())
                .filter(p -> p.name().equalsIgnoreCase(raw))
                .findFirst()
                .orElse(ProductType.FEED);
    }
}
