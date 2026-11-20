package com.rvmendillo.igstats.ig;

import com.fasterxml.jackson.databind.JsonNode;
import com.rvmendillo.igstats.config.IgProperties;
import com.rvmendillo.igstats.ig.IgMetricCatalog.LoginType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Thin client over the Instagram Platform API.
 *
 * <p>Host depends on the login path the account was connected through:
 * {@code graph.instagram.com} for Instagram Login, {@code graph.facebook.com}
 * for Facebook Login. The endpoint shapes are otherwise identical, which is why
 * one client serves both.
 *
 * <p>Error handling is the substance of this class. The API fails in specific,
 * meaningful ways and conflating them loses information you cannot recover:
 * code 10 on a story means "fewer than 5 viewers", which is data, not an error.
 * Code 100 means you asked for a metric this media type does not carry, which
 * is a bug in your metric string. Code 190 means the token is gone and every
 * subsequent call this hour will also fail, so stop.
 */
@Component
public class GraphApiClient {

    private static final Logger log = LoggerFactory.getLogger(GraphApiClient.class);

    /** Fewer than 5 viewers on a story. Not a failure - a privacy floor. */
    public static final int ERR_NOT_ENOUGH_VIEWERS = 10;
    /** Unsupported metric for this media type, or a malformed breakdown pairing. */
    public static final int ERR_UNSUPPORTED = 100;
    /** Token expired or revoked. Re-auth required; retrying will not help. */
    public static final int ERR_OAUTH = 190;
    /** Rate limit. Back off - an invalid request still consumes quota. */
    public static final int ERR_RATE_LIMIT = 4;
    public static final int ERR_RATE_LIMIT_USER = 17;

    private final RestClient instagramHost;
    private final RestClient facebookHost;
    private final IgProperties props;

    public GraphApiClient(RestClient.Builder builder, IgProperties props) {
        this.props = props;
        this.instagramHost = builder.clone()
                .baseUrl("https://graph.instagram.com/" + props.apiVersion()).build();
        this.facebookHost = builder.clone()
                .baseUrl("https://graph.facebook.com/" + props.apiVersion()).build();
    }

    private RestClient host(LoginType login) {
        return login == LoginType.FACEBOOK ? facebookHost : instagramHost;
    }

    /**
     * The node to address for "this account". With Instagram Login the token
     * identifies the user, so {@code me} is unambiguous - and sidesteps the two
     * different ids that path hands out (app-scoped {@code id} vs professional
     * {@code user_id}). Facebook Login has no such alias for the IG user.
     */
    static String node(LoginType login, String igUserId) {
        return login == LoginType.INSTAGRAM ? "me" : igUserId;
    }

    // ------------------------------------------------------------------
    // Core GET
    // ------------------------------------------------------------------

    public JsonNode get(LoginType login, String path, Map<String, String> params, String token) {
        Map<String, String> all = new LinkedHashMap<>(params);
        if (token != null && !token.isBlank()) {
            all.put("access_token", token);
        }

        try {
            JsonNode body = host(login).get()
                    .uri(uri -> buildUri(uri, path, all))
                    .retrieve()
                    .body(JsonNode.class);

            if (body != null && body.has("error")) {
                throw IgApiException.from(body.get("error"));
            }
            return body;

        } catch (RestClientResponseException e) {
            // Meta returns structured errors with 4xx status codes; the body is
            // far more useful than the status, so unwrap before giving up.
            Optional<IgApiException> parsed = IgApiException.tryParse(e.getResponseBodyAsString());
            throw parsed.orElseGet(() -> new IgApiException(
                    e.getStatusCode().value(), null, "HTTP " + e.getStatusCode(), e));
        }
    }

    /**
     * Values go in as URI variables, never as literal query strings. Business
     * Discovery's field syntax - {@code username(x){a,b,media.limit(12){...}}} -
     * is full of braces, which a literal query param would have UriBuilder parse
     * as template placeholders and fail on. Variables are fully encoded instead.
     */
    private java.net.URI buildUri(UriBuilder uri, String path, Map<String, String> params) {
        UriBuilder b = uri.path(path.startsWith("/") ? path : "/" + path);
        Map<String, String> vars = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, String> e : params.entrySet()) {
            String var = "v" + (i++);
            b.queryParam(e.getKey(), "{" + var + "}");
            vars.put(var, e.getValue());
        }
        return b.build(vars);
    }

    // ------------------------------------------------------------------
    // Account
    // ------------------------------------------------------------------

    public JsonNode me(LoginType login, String igUserId, String token) {
        return get(login, node(login, igUserId),
                Map.of("fields", IgMetricCatalog.accountFields(login)), token);
    }

    /**
     * Account insights as a per-day series.
     *
     * <p>Note the window: omitting since/until makes the API look back only 24
     * hours, which is the single most common reason a first integration appears
     * to return nothing at all. Always pass an explicit range.
     */
    public JsonNode accountTimeSeries(LoginType login, String igUserId,
                                      Instant since, Instant until, String token) {
        return get(login, node(login, igUserId) + "/insights", Map.of(
                "metric", IgMetricCatalog.accountTimeSeriesMetrics(),
                "metric_type", "time_series",
                "period", "day",
                "since", String.valueOf(since.getEpochSecond()),
                "until", String.valueOf(until.getEpochSecond())
        ), token);
    }

    /** Account totals over a window, optionally split by one breakdown dimension. */
    public JsonNode accountTotals(LoginType login, String igUserId, String metrics,
                                  String breakdown, Instant since, Instant until, String token) {
        Map<String, String> p = new LinkedHashMap<>(Map.of(
                "metric", metrics,
                "metric_type", "total_value",
                "period", "day",
                "since", String.valueOf(since.getEpochSecond()),
                "until", String.valueOf(until.getEpochSecond())
        ));
        if (breakdown != null && !breakdown.isBlank()) {
            p.put("breakdown", breakdown);
        }
        return get(login, node(login, igUserId) + "/insights", p, token);
    }

    /**
     * Demographics. Needs {@code period=lifetime} plus a timeframe, and returns
     * nothing below 100 followers - the API does not say so, it just comes back
     * empty, so treat an empty result as "not eligible yet" rather than an error.
     */
    public JsonNode demographics(LoginType login, String igUserId, String metric,
                                 String breakdown, String timeframe, String token) {
        Map<String, String> p = new LinkedHashMap<>(Map.of(
                "metric", metric,
                "period", "lifetime",
                "metric_type", "total_value",
                "breakdown", breakdown
        ));
        if (timeframe != null && !timeframe.isBlank()) {
            p.put("timeframe", timeframe);
        }
        return get(login, node(login, igUserId) + "/insights", p, token);
    }

    // ------------------------------------------------------------------
    // Media
    // ------------------------------------------------------------------

    public JsonNode media(LoginType login, String igUserId, int limit, String after, String token) {
        Map<String, String> p = new LinkedHashMap<>(Map.of(
                "fields", IgMetricCatalog.MEDIA_FIELDS,
                "limit", String.valueOf(limit)
        ));
        if (after != null && !after.isBlank()) {
            p.put("after", after);
        }
        return get(login, node(login, igUserId) + "/media", p, token);
    }

    /**
     * Live stories. This edge only ever returns what is currently unexpired -
     * there is no historical story endpoint at any access tier. Whatever this
     * misses is gone permanently, which is the entire reason the archive table
     * exists.
     */
    public JsonNode stories(LoginType login, String igUserId, String token) {
        return get(login, node(login, igUserId) + "/stories",
                Map.of("fields", IgMetricCatalog.STORY_FIELDS), token);
    }

    public JsonNode mediaInsights(LoginType login, String mediaId, String metrics, String token) {
        return get(login, mediaId + "/insights", Map.of("metric", metrics), token);
    }

    /** A breakdown request must stand alone - one metric, one breakdown. */
    public JsonNode mediaInsightsBreakdown(LoginType login, String mediaId,
                                           String metric, String breakdown, String token) {
        return get(login, mediaId + "/insights",
                Map.of("metric", metric, "breakdown", breakdown), token);
    }

    // ------------------------------------------------------------------
    // Business Discovery - the sanctioned profile analyzer
    // ------------------------------------------------------------------

    /**
     * Read another account's public profile and recent media.
     *
     * <p>Constraints that are not optional: Facebook Login path only, the caller
     * must be a professional account, and <b>the target must also be a Business
     * or Creator account</b>. Personal accounts are unreachable - there is no
     * official endpoint for them, which is exactly the gap unofficial scrapers
     * fill and exactly why they get accounts banned.
     *
     * <p>The id in the path is <i>your</i> account, not the target's. Meta
     * answers on your behalf.
     */
    public JsonNode businessDiscovery(String ownIgUserId, String targetUsername,
                                      int mediaLimit, String token) {
        String fields = "business_discovery.username(%s){%s,media.limit(%d){%s}}".formatted(
                targetUsername,
                IgMetricCatalog.DISCOVERY_FIELDS,
                mediaLimit,
                IgMetricCatalog.DISCOVERY_MEDIA_FIELDS);
        return get(LoginType.FACEBOOK, ownIgUserId, Map.of("fields", fields), token);
    }

    // ------------------------------------------------------------------
    // Tokens
    // ------------------------------------------------------------------

    /**
     * Refresh a long-lived Instagram token. Must be at least 24 hours old and not
     * yet expired; the result is good for another 60 days. Miss the window and
     * there is no recovery path - the user has to re-authorise from scratch.
     */
    public JsonNode refreshInstagramToken(String longLivedToken) {
        return get(LoginType.INSTAGRAM, "refresh_access_token",
                Map.of("grant_type", "ig_refresh_token"), longLivedToken);
    }

    /** Exchange a short-lived Facebook token for a ~60 day one. */
    public JsonNode exchangeFacebookToken(String shortLivedToken) {
        return get(LoginType.FACEBOOK, "oauth/access_token", Map.of(
                "grant_type", "fb_exchange_token",
                "client_id", props.appId(),
                "client_secret", props.appSecret(),
                "fb_exchange_token", shortLivedToken
        ), "");
    }

    // ------------------------------------------------------------------
    // Retry helper
    // ------------------------------------------------------------------

    /**
     * Run a call, retrying only what is worth retrying.
     *
     * <p>Rate limits are retried with backoff. Everything else is not: an expired
     * token will not fix itself, and an unsupported metric is a code bug. Retrying
     * either just burns quota - and note that a rejected request still counts
     * against your hourly budget.
     */
    public <T> T withRetry(Supplier<T> call, int maxAttempts) {
        IgApiException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (IgApiException e) {
                last = e;
                if (!e.isRetryable() || attempt == maxAttempts) {
                    throw e;
                }
                Duration wait = Duration.ofSeconds((long) Math.pow(2, attempt) * 5);
                log.warn("IG API rate limited (code {}), attempt {}/{}, waiting {}s",
                        e.getCode(), attempt, maxAttempts, wait.toSeconds());
                try {
                    Thread.sleep(wait.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    // ------------------------------------------------------------------
    // Tolerant multi-metric requests
    // ------------------------------------------------------------------

    /**
     * Request several metrics at once, surviving a metric Meta stopped
     * supporting.
     *
     * <p>One unsupported metric fails the whole request with code 100, and Meta
     * retires metrics with little notice. So on a 100 the list is split in half
     * and each half retried, recursively, until the offending metric is isolated
     * and reported. Every other metric is still collected. Costs at most
     * {@code 2n-1} calls in the worst case and exactly one when nothing is wrong.
     *
     * @param metrics       metrics to fetch
     * @param call          performs one request for a comma-joined metric string
     * @param onUnsupported told about each metric that failed on its own
     */
    public List<InsightRow> tolerant(List<String> metrics,
                                     Function<String, JsonNode> call,
                                     Consumer<String> onUnsupported) {
        if (metrics.isEmpty()) {
            return List.of();
        }
        try {
            return parseInsights(call.apply(String.join(",", metrics)));
        } catch (IgApiException e) {
            if (!e.isUnsupportedMetric()) {
                throw e;
            }
            if (metrics.size() == 1) {
                onUnsupported.accept(metrics.get(0));
                return List.of();
            }
            int mid = metrics.size() / 2;
            List<InsightRow> out = new ArrayList<>(tolerant(metrics.subList(0, mid), call, onUnsupported));
            out.addAll(tolerant(metrics.subList(mid, metrics.size()), call, onUnsupported));
            return out;
        }
    }

    // ------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------

    /**
     * Flatten an insights response into rows.
     *
     * <p>The payload shape differs by request type, which the API does not
     * advertise: {@code time_series} puts numbers under {@code values[]} with
     * {@code end_time}; {@code total_value} puts a single number under
     * {@code total_value.value}; a broken-down {@code total_value} puts them
     * under {@code total_value.breakdowns[].results[]} with {@code dimension_values}.
     * All three arrive from the same endpoint.
     */
    public List<InsightRow> parseInsights(JsonNode response) {
        List<InsightRow> rows = new java.util.ArrayList<>();
        JsonNode data = response == null ? null : response.get("data");
        if (data == null || !data.isArray()) {
            return rows;
        }

        for (JsonNode metric : data) {
            String name = text(metric, "name");
            String period = text(metric, "period");

            // Shape 1: time series
            JsonNode values = metric.get("values");
            if (values != null && values.isArray() && !values.isEmpty()) {
                for (JsonNode v : values) {
                    JsonNode val = v.get("value");
                    if (val != null && val.isObject()) {
                        // Demographics arrive as an object of dimension -> count
                        val.properties().forEach(f -> rows.add(new InsightRow(
                                name, period, "", f.getKey(), f.getValue().asLong(),
                                text(v, "end_time"))));
                    } else if (val != null) {
                        rows.add(new InsightRow(name, period, "", "", val.asLong(),
                                text(v, "end_time")));
                    }
                }
                continue;
            }

            // Shapes 2 and 3: total_value, with or without breakdowns
            JsonNode total = metric.get("total_value");
            if (total == null) {
                continue;
            }

            JsonNode breakdowns = total.get("breakdowns");
            if (breakdowns != null && breakdowns.isArray() && !breakdowns.isEmpty()) {
                for (JsonNode bd : breakdowns) {
                    String dimName = bd.path("dimension_keys").path(0).asText("");
                    JsonNode results = bd.get("results");
                    if (results == null) continue;
                    for (JsonNode r : results) {
                        rows.add(new InsightRow(
                                name, period, dimName,
                                r.path("dimension_values").path(0).asText(""),
                                r.path("value").asLong(),
                                null));
                    }
                }
            } else if (total.has("value")) {
                rows.add(new InsightRow(name, period, "", "", total.get("value").asLong(), null));
            }
        }
        return rows;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    /**
     * One parsed metric value.
     *
     * @param metric    API metric name
     * @param period    day | week | lifetime | ...
     * @param breakdown dimension key, empty when unbroken
     * @param dimension dimension value, empty when unbroken
     * @param value     the number
     * @param endTime   ISO timestamp for series points, null for totals
     */
    public record InsightRow(String metric, String period, String breakdown,
                             String dimension, long value, String endTime) {
    }
}
