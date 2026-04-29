package io.sitprep.sitprepapi.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

/**
 * Initializes the Firebase Admin SDK so {@link com.google.firebase.auth.FirebaseAuth}
 * can verify ID tokens from the FE.
 *
 * <p><b>Two init paths, env vars first.</b> Production runs on Heroku where the
 * service-account JSON cannot be committed to git (gitignored — a service
 * account is a secret), so we build credentials from
 * {@code FIREBASE_*} config vars instead. Local dev keeps working from the
 * classpath JSON when present (engineers who have it dropped at
 * {@code src/main/resources/sitprep-new-firebase-adminsdk-june24.json} get
 * the same behavior they always had).</p>
 *
 * <p><b>Failure mode:</b> if neither path produces a valid FirebaseApp, throw
 * {@link IllegalStateException} on startup so the dyno crashes loudly. The
 * previous behavior silently let init fail, which made every authed endpoint
 * return 401 with no clear cause — see the {@code FirebaseApp with name
 * [DEFAULT] doesn't exist} warnings that were buried in WARN logs.</p>
 *
 * <p>Required env vars when classpath JSON is absent:
 * {@code FIREBASE_CLIENT_EMAIL}, {@code FIREBASE_PRIVATE_KEY},
 * {@code FIREBASE_PRIVATE_KEY_ID}. Optional: {@code FIREBASE_CLIENT_ID}
 * (helpful for some Google APIs but not strictly required by the Admin SDK).</p>
 */
@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);
    private static final String CLASSPATH_JSON = "sitprep-new-firebase-adminsdk-june24.json";

    @PostConstruct
    public void initialize() {
        // Idempotency — Spring is supposed to call @PostConstruct once per
        // bean, but in tests with @SpringBootTest the context can refresh
        // and double-invoke. Guard so we don't crash on duplicate init.
        if (!FirebaseApp.getApps().isEmpty()) {
            log.info("FirebaseApp already initialized; skipping.");
            return;
        }

        // Env-var path first — this is what works in prod.
        if (initFromEnvVars()) return;

        // Local-dev fallback: classpath JSON if an engineer has dropped
        // the service-account file at src/main/resources/.
        if (initFromClasspathJson()) return;

        throw new IllegalStateException(
                "Could not initialize FirebaseApp. Set FIREBASE_CLIENT_EMAIL, " +
                "FIREBASE_PRIVATE_KEY, and FIREBASE_PRIVATE_KEY_ID env vars " +
                "(production), or place " + CLASSPATH_JSON + " on the classpath " +
                "(local dev).");
    }

    private boolean initFromEnvVars() {
        String projectId = trimOrNull(System.getenv("FIREBASE_PROJECT_ID"));
        String clientEmail = trimOrNull(System.getenv("FIREBASE_CLIENT_EMAIL"));
        String privateKeyEscaped = System.getenv("FIREBASE_PRIVATE_KEY");
        String privateKeyId = trimOrNull(System.getenv("FIREBASE_PRIVATE_KEY_ID"));
        String clientId = trimOrNull(System.getenv("FIREBASE_CLIENT_ID")); // optional

        if (projectId == null || clientEmail == null
                || privateKeyEscaped == null || privateKeyEscaped.isBlank()
                || privateKeyId == null) {
            log.info("FirebaseConfig: env-var path not available (one or more " +
                    "FIREBASE_* vars missing); will try classpath JSON fallback.");
            return false;
        }

        // Heroku stores the PEM private key with literal "\n" sequences;
        // ServiceAccountCredentials.fromPkcs8 expects real newlines.
        String privateKey = privateKeyEscaped.replace("\\n", "\n").trim();

        try {
            ServiceAccountCredentials credentials = (ServiceAccountCredentials)
                    ServiceAccountCredentials.fromPkcs8(
                            clientId,        // null is fine — Admin SDK doesn't need it
                            clientEmail,
                            privateKey,
                            privateKeyId,
                            null             // default scopes; Firebase Admin SDK sets these
                    );

            // setProjectId is REQUIRED when credentials are built via
            // fromPkcs8 (vs GoogleCredentials.fromStream(json) which infers
            // it from the JSON's project_id field). Without it,
            // FirebaseAuth.verifyIdToken throws "Must initialize FirebaseApp
            // with a project ID to call verifyIdToken()".
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setProjectId(projectId)
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("FirebaseApp initialized from env vars (project={}, account={}).",
                    projectId, clientEmail);
            return true;
        } catch (IOException | RuntimeException e) {
            log.error("FirebaseConfig: env-var init failed: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean initFromClasspathJson() {
        try (var stream = new ClassPathResource(CLASSPATH_JSON).getInputStream()) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(stream))
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("FirebaseApp initialized from classpath JSON ({}).", CLASSPATH_JSON);
            return true;
        } catch (IOException e) {
            log.info("FirebaseConfig: classpath JSON not present ({}). " +
                    "This is expected in production; local dev should drop the file at " +
                    "src/main/resources/.", e.getMessage());
            return false;
        }
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
