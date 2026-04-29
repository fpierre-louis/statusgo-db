package io.sitprep.sitprepapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Builds the S3-compatible client SitPrep uses to talk to Cloudflare R2.
 *
 * Required env vars (mirrored on Heroku and in {@code application-local.yml}):
 *   R2_ACCOUNT_ID         — the Cloudflare account ID
 *   R2_ACCESS_KEY_ID      — Object Read & Write token
 *   R2_SECRET_ACCESS_KEY  — same token's secret
 *
 * Endpoint is derived from the account id; no separate env var needed.
 */
@Configuration
public class R2Config {

    @Value("${r2.account-id:}")
    private String accountId;

    @Value("${r2.access-key-id:}")
    private String accessKeyId;

    @Value("${r2.secret-access-key:}")
    private String secretAccessKey;

    @Bean
    public S3Client r2Client() {
        // Trim before *every* use — a trailing space in R2_ACCOUNT_ID was
        // pasted into the Heroku config var on 2026-04-28 and crashed the
        // app on startup with "Illegal character in authority at index 40:
        // https://<id> .r2.cloudflarestorage.com". Defense in depth: trim
        // here so the next config typo doesn't take prod down.
        String account = trim(accountId);
        String key = trim(accessKeyId);
        String secret = trim(secretAccessKey);

        if (isBlank(account) || isBlank(key) || isBlank(secret)) {
            throw new IllegalStateException(
                    "Missing R2 env vars. Set R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY " +
                    "on Heroku (or in application-local.yml for local dev).");
        }

        AwsBasicCredentials credentials = AwsBasicCredentials.create(key, secret);
        URI endpoint = URI.create("https://" + account + ".r2.cloudflarestorage.com");

        return S3Client.builder()
                .endpointOverride(endpoint)
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.US_EAST_1) // R2 ignores region; SDK requires one
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
