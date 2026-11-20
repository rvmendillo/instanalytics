package com.rvmendillo.igstats.ig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rvmendillo.igstats.config.IgProperties;
import com.rvmendillo.igstats.ig.GraphApiClient.InsightRow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GraphApiClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static IgProperties props() {
        return new IgProperties("app", "secret", "http://localhost/cb", "v26.0",
                null, ZoneId.of("Asia/Manila"), Duration.ofMinutes(30), 7);
    }

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    // ------------------------------------------------------------------
    // Parsing: the same endpoint returns three different shapes.
    // ------------------------------------------------------------------

    private final GraphApiClient parser = new GraphApiClient(RestClient.builder(), props());

    @Test
    void parsesTimeSeries() throws Exception {
        List<InsightRow> rows = parser.parseInsights(json("""
                {"data":[{"name":"reach","period":"day","values":[
                  {"value":120,"end_time":"2026-11-19T08:00:00+0000"},
                  {"value":95,"end_time":"2026-11-20T08:00:00+0000"}]}]}
                """));
        assertThat(rows).extracting(InsightRow::value).containsExactly(120L, 95L);
        assertThat(rows.get(1).endTime()).isEqualTo("2026-11-20T08:00:00+0000");
    }

    @Test
    void parsesStoryScalarShape() throws Exception {
        // Media insights: values[] with no end_time.
        List<InsightRow> rows = parser.parseInsights(json("""
                {"data":[{"name":"reach","period":"lifetime","values":[{"value":42}]},
                         {"name":"replies","period":"lifetime","values":[{"value":3}]}]}
                """));
        assertThat(rows).extracting(InsightRow::metric).containsExactly("reach", "replies");
        assertThat(rows).extracting(InsightRow::value).containsExactly(42L, 3L);
    }

    @Test
    void parsesTotalValue() throws Exception {
        List<InsightRow> rows = parser.parseInsights(json("""
                {"data":[{"name":"views","period":"day","total_value":{"value":431}}]}
                """));
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.metric()).isEqualTo("views");
            assertThat(r.value()).isEqualTo(431L);
            assertThat(r.breakdown()).isEmpty();
        });
    }

    @Test
    void parsesNavigationBreakdown() throws Exception {
        List<InsightRow> rows = parser.parseInsights(json("""
                {"data":[{"name":"navigation","period":"lifetime","total_value":{"value":120,
                  "breakdowns":[{"dimension_keys":["story_navigation_action_type"],"results":[
                    {"dimension_values":["tap_forward"],"value":70},
                    {"dimension_values":["tap_back"],"value":10},
                    {"dimension_values":["tap_exit"],"value":15},
                    {"dimension_values":["swipe_forward"],"value":25}]}]}}]}
                """));
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(InsightRow::breakdown).containsOnly("story_navigation_action_type");
        assertThat(rows).extracting(InsightRow::dimension)
                .containsExactly("tap_forward", "tap_back", "tap_exit", "swipe_forward");
        // The four actions sum to the navigation total.
        assertThat(rows.stream().mapToLong(InsightRow::value).sum()).isEqualTo(120L);
    }

    @Test
    void parsesDemographicsBreakdown() throws Exception {
        List<InsightRow> rows = parser.parseInsights(json("""
                {"data":[{"name":"follower_demographics","period":"lifetime","total_value":{
                  "breakdowns":[{"dimension_keys":["age"],"results":[
                    {"dimension_values":["25-34"],"value":120},
                    {"dimension_values":["18-24"],"value":80}]}]}}]}
                """));
        assertThat(rows).extracting(InsightRow::dimension).containsExactly("25-34", "18-24");
    }

    @Test
    void emptyOrMissingDataIsEmptyNotAnError() throws Exception {
        assertThat(parser.parseInsights(json("{\"data\":[]}"))).isEmpty();
        assertThat(parser.parseInsights(json("{}"))).isEmpty();
        assertThat(parser.parseInsights(null)).isEmpty();
    }

    // ------------------------------------------------------------------
    // HTTP: URL building and error mapping.
    // ------------------------------------------------------------------

    @Test
    void businessDiscoveryBracesSurviveUrlBuilding() {
        // Regression: braces in the fields syntax were parsed as URI template
        // placeholders and the request never left the client.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GraphApiClient client = new GraphApiClient(builder, props());

        server.expect(request -> {
                    assertThat(request.getURI().getHost()).isEqualTo("graph.facebook.com");
                    assertThat(request.getURI().getRawPath()).isEqualTo("/v26.0/17841400000000000");
                    String query = URLDecoder.decode(request.getURI().getRawQuery(), StandardCharsets.UTF_8);
                    assertThat(query)
                            .contains("fields=business_discovery.username(somebrand){username,")
                            .contains("media.limit(12){id,caption,")
                            .contains("access_token=tok");
                })
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"business_discovery":{"username":"somebrand","followers_count":5120}}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = client.businessDiscovery("17841400000000000", "somebrand", 12, "tok");
        assertThat(result.path("business_discovery").path("followers_count").asInt()).isEqualTo(5120);
        server.verify();
    }

    @Test
    void lowVolumeStoryErrorIsRecognised() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GraphApiClient client = new GraphApiClient(builder, props());

        server.expect(request -> { })
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"message":"(#10) Not enough viewers for the media to show insights",
                                          "type":"OAuthException","code":10}}
                                """));

        assertThatThrownBy(() -> client.mediaInsights(
                IgMetricCatalog.LoginType.INSTAGRAM, "123", "reach", "tok"))
                .isInstanceOfSatisfying(IgApiException.class, e -> {
                    assertThat(e.isLowVolume()).isTrue();
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.isAuthFailure()).isFalse();
                });
    }

    @Test
    void emptyTokenIsNotSentAsAParameter() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GraphApiClient client = new GraphApiClient(builder, props());

        server.expect(request -> assertThat(request.getURI().getRawQuery()).doesNotContain("access_token"))
                .andRespond(withSuccess("{\"access_token\":\"long\",\"expires_in\":5183944}",
                        MediaType.APPLICATION_JSON));

        client.exchangeFacebookToken("short");
        server.verify();
    }

    // ------------------------------------------------------------------
    // Tolerant requests: survive a metric Meta retires.
    // ------------------------------------------------------------------

    /** Fake endpoint: rejects any request containing `bad`, else returns 1 per metric. */
    private static JsonNode fakeInsights(String metrics, String bad) {
        if (java.util.Arrays.asList(metrics.split(",")).contains(bad)) {
            throw new IgApiException(100, null, "(#100) The metric " + bad + " is not supported", null);
        }
        StringBuilder sb = new StringBuilder("{\"data\":[");
        String[] ms = metrics.split(",");
        for (int i = 0; i < ms.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(ms[i]).append("\",\"values\":[{\"value\":1}]}");
        }
        try {
            return JSON.readTree(sb.append("]}").toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void tolerantIsolatesTheRejectedMetricAndKeepsTheRest() {
        List<String> rejected = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        List<InsightRow> rows = parser.tolerant(List.of("a", "b", "c", "d", "e"), m -> {
            calls.incrementAndGet();
            return fakeInsights(m, "c");
        }, rejected::add);

        assertThat(rows).extracting(InsightRow::metric).containsExactlyInAnyOrder("a", "b", "d", "e");
        assertThat(rejected).containsExactly("c");
        assertThat(calls.get()).isLessThanOrEqualTo(2 * 5 - 1);
    }

    @Test
    void tolerantCostsOneCallWhenNothingIsWrong() {
        AtomicInteger calls = new AtomicInteger();
        List<InsightRow> rows = parser.tolerant(List.of("a", "b", "c"), m -> {
            calls.incrementAndGet();
            return fakeInsights(m, "none");
        }, x -> { });
        assertThat(rows).hasSize(3);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void tolerantDoesNotSwallowAuthFailures() {
        assertThatThrownBy(() -> parser.tolerant(List.of("a", "b"),
                m -> { throw new IgApiException(190, null, "(#190) expired", null); }, x -> { }))
                .isInstanceOfSatisfying(IgApiException.class, e -> assertThat(e.isAuthFailure()).isTrue());
    }

    @Test
    void instagramLoginAddressesMeNotTheStoredId() {
        assertThat(GraphApiClient.node(IgMetricCatalog.LoginType.INSTAGRAM, "17841400000000000")).isEqualTo("me");
        assertThat(GraphApiClient.node(IgMetricCatalog.LoginType.FACEBOOK, "17841400000000000"))
                .isEqualTo("17841400000000000");
    }

    @Test
    void facebookLoginNeverAsksForAccountType() {
        assertThat(IgMetricCatalog.accountFields(IgMetricCatalog.LoginType.FACEBOOK)).doesNotContain("account_type");
        assertThat(IgMetricCatalog.accountFields(IgMetricCatalog.LoginType.INSTAGRAM)).contains("account_type");
    }
}
