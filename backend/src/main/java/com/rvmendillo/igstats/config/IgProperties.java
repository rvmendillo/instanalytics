package com.rvmendillo.igstats.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;

/**
 * App configuration. Secrets come from the environment, never the yml.
 *
 * @param appId            Meta app id
 * @param appSecret        Meta app secret
 * @param redirectUri      OAuth redirect registered in the Meta app dashboard
 * @param apiVersion       Graph API version, e.g. v26.0
 * @param tokenKey         base64 AES-256 key used to encrypt tokens at rest
 * @param zone             zone used to bucket stories into days - matters for
 *                         frame ordering, since "today's story" is a local idea
 * @param storyPollEvery   how often to capture live stories. Must be well under
 *                         24h or frames posted and expiring between polls are
 *                         lost forever; 30 minutes leaves wide margin
 * @param refreshTokenDays refresh long-lived tokens once they are this old.
 *                         Tokens last 60 days and must be 24h old to refresh
 */
@ConfigurationProperties(prefix = "ig")
public record IgProperties(
        String appId,
        String appSecret,
        String redirectUri,
        @DefaultValue("v26.0") String apiVersion,
        String tokenKey,
        @DefaultValue("Asia/Manila") ZoneId zone,
        @DefaultValue("PT30M") Duration storyPollEvery,
        @DefaultValue("7") int refreshTokenDays
) {
}
