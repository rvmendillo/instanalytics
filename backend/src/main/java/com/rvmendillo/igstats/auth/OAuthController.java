package com.rvmendillo.igstats.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.rvmendillo.igstats.config.IgProperties;
import com.rvmendillo.igstats.ig.IgMetricCatalog.LoginType;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Business Login for Instagram - connects your professional account.
 *
 * <p>Flow: authorize -> code -> short-lived token (1h) -> long-lived token (60d).
 * The code is single-use and valid for one hour, so the callback exchanges it
 * immediately.
 *
 * <p>Your account must be Business or Creator. Personal accounts have had no API
 * access at all since Basic Display shut down on 2024-12-04; switching to Creator
 * is free, reversible, and takes a minute in the app settings.
 */
@RestController
@RequestMapping("/auth")
public class OAuthController {

    private static final String SCOPES = String.join(",",
            "instagram_business_basic",
            "instagram_business_manage_insights");

    private final IgProperties props;
    private final AccountStore accounts;
    private final RestClient http;

    /** CSRF state. Single-user app, so in-memory is enough. */
    private final Map<String, Instant> pendingStates = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public OAuthController(IgProperties props, AccountStore accounts, RestClient.Builder builder) {
        this.props = props;
        this.accounts = accounts;
        this.http = builder.build();
    }

    @GetMapping("/instagram")
    public ResponseEntity<Void> start() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        pendingStates.put(state, Instant.now());

        URI uri = UriComponentsBuilder.fromUriString("https://www.instagram.com/oauth/authorize")
                .queryParam("client_id", props.appId())
                .queryParam("redirect_uri", props.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPES)
                .queryParam("state", state)
                .build().encode().toUri();

        return ResponseEntity.status(302).location(uri).build();
    }

    @GetMapping("/instagram/callback")
    public Map<String, Object> callback(@RequestParam String code, @RequestParam String state) {
        Instant issued = pendingStates.remove(state);
        if (issued == null || issued.isBefore(Instant.now().minus(Duration.ofMinutes(10)))) {
            throw new IllegalArgumentException("Invalid or expired OAuth state");
        }

        // Instagram appends "#_" to the code in some redirects. Strip it.
        String cleanCode = code.replace("#_", "");

        // 1. code -> short-lived token
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", props.appId());
        form.add("client_secret", props.appSecret());
        form.add("grant_type", "authorization_code");
        form.add("redirect_uri", props.redirectUri());
        form.add("code", cleanCode);

        JsonNode shortLived = http.post()
                .uri("https://api.instagram.com/oauth/access_token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);

        String shortToken = shortLived.path("access_token").asText();

        // 2. short-lived -> long-lived (60 days)
        JsonNode longLived = http.get()
                .uri(UriComponentsBuilder.fromUriString("https://graph.instagram.com/access_token")
                        .queryParam("grant_type", "ig_exchange_token")
                        .queryParam("client_secret", props.appSecret())
                        .queryParam("access_token", shortToken)
                        .build().toUri())
                .retrieve()
                .body(JsonNode.class);

        String token = longLived.path("access_token").asText();
        long expiresIn = longLived.path("expires_in").asLong(Duration.ofDays(60).toSeconds());

        // 3. Resolve the professional account id. This is the id every insights
        //    call is keyed on - not the app-scoped id from step 1.
        JsonNode me = http.get()
                .uri(UriComponentsBuilder.fromUriString(
                                "https://graph.instagram.com/" + props.apiVersion() + "/me")
                        .queryParam("fields", "user_id,username,account_type")
                        .queryParam("access_token", token)
                        .build().toUri())
                .retrieve()
                .body(JsonNode.class);

        String igUserId = me.path("user_id").asText();
        String username = me.path("username").asText();

        long id = accounts.upsert(igUserId, username, LoginType.INSTAGRAM, token,
                Instant.now().plusSeconds(expiresIn));

        return Map.of(
                "connected", true,
                "accountId", id,
                "username", username,
                "accountType", me.path("account_type").asText(""),
                "tokenValidDays", Duration.ofSeconds(expiresIn).toDays(),
                "next", "Story capture starts within 30 minutes. Post a story to test it.");
    }

    /**
     * The shortcut for a single personal account: skip OAuth entirely.
     *
     * <p>Meta's App Dashboard (Instagram -> API setup with Instagram login ->
     * Generate access tokens) issues a long-lived token for any account with a
     * role on your app. Paste it here. This sidesteps the redirect URI, which
     * Meta can require to be HTTPS - awkward for a server on your home network.
     *
     * <p>Unauthenticated on purpose for a LAN-only personal server. Do not expose
     * this backend to the internet without putting auth in front of it.
     */
    @PostMapping("/token")
    public Map<String, Object> importToken(@RequestBody Map<String, String> body) {
        String token = body.getOrDefault("accessToken", "").trim();
        if (token.isEmpty()) {
            throw new IllegalArgumentException("Body must be {\"accessToken\": \"...\"}");
        }

        JsonNode me = http.get()
                .uri(UriComponentsBuilder.fromUriString(
                                "https://graph.instagram.com/" + props.apiVersion() + "/me")
                        .queryParam("fields", "user_id,username,account_type")
                        .queryParam("access_token", token)
                        .build().toUri())
                .retrieve()
                .body(JsonNode.class);

        // Expiry isn't returned for a pasted token. Assume 60 days and stamp it
        // as just refreshed: TokenRefreshJob renews it within a week, which also
        // covers a token that was generated some time ago.
        long id = accounts.upsert(me.path("user_id").asText(), me.path("username").asText(),
                LoginType.INSTAGRAM, token, Instant.now().plus(Duration.ofDays(60)));

        return Map.of(
                "connected", true,
                "accountId", id,
                "username", me.path("username").asText(),
                "accountType", me.path("account_type").asText(""));
    }
}
