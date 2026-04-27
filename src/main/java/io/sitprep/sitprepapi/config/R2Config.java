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
        if (isBlank(accountId) || isBlank(accessKeyId) || isBlank(secretAccessKey)) {
            throw new IllegalStateException(
                    "Missing R2 env vars. Set R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY " +
                    "on Heroku (or in application-local.yml for local dev).");
        }

        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
        URI endpoint = URI.create("https://" + accountId + ".r2.cloudflarestorage.com");

        return S3Client.builder()
                .endpointOverride(endpoint)
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.US_EAST_1) // R2 ignores region; SDK requires one
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
