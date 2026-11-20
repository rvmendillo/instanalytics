package com.rvmendillo.igstats.ig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * A structured Instagram API error.
 *
 * <p>Meta's error codes carry meaning a status code does not, so this keeps
 * them intact instead of collapsing everything into "request failed".
 */
public class IgApiException extends RuntimeException {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int code;
    private final Integer subcode;

    public IgApiException(int code, Integer subcode, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.subcode = subcode;
    }

    public static IgApiException from(JsonNode error) {
        int code = error.path("code").asInt(-1);
        Integer sub = error.has("error_subcode") ? error.get("error_subcode").asInt() : null;
        String msg = error.path("message").asText("Unknown Instagram API error");
        return new IgApiException(code, sub, "(#" + code + ") " + msg, null);
    }

    static Optional<IgApiException> tryParse(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode err = root.get("error");
            return err == null ? Optional.empty() : Optional.of(from(err));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public int getCode() {
        return code;
    }

    public Integer getSubcode() {
        return subcode;
    }

    /** Fewer than 5 viewers. For stories this is a data point, not a failure. */
    public boolean isLowVolume() {
        return code == GraphApiClient.ERR_NOT_ENOUGH_VIEWERS;
    }

    /** Token is dead. Every further call for this account will fail too. */
    public boolean isAuthFailure() {
        return code == GraphApiClient.ERR_OAUTH;
    }

    /** Asked for a metric this media type does not carry - a catalog bug. */
    public boolean isUnsupportedMetric() {
        return code == GraphApiClient.ERR_UNSUPPORTED;
    }

    /** Only rate limits are worth retrying. */
    public boolean isRetryable() {
        return code == GraphApiClient.ERR_RATE_LIMIT
                || code == GraphApiClient.ERR_RATE_LIMIT_USER
                || code == 32 || code == 613;
    }
}
