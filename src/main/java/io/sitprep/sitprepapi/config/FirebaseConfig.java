package io.sitprep.sitprepapi.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class FirebaseConfig {

    @PostConstruct
    public void initialize() {
        try {
            String firebaseConfig = "{"
                    + "\"type\": \"service_account\","
                    + "\"project_id\": \"" + System.getenv("FIREBASE_PROJECT_ID") + "\","
                    + "\"private_key_id\": \"" + System.getenv("FIREBASE_PRIVATE_KEY_ID") + "\","
                    + "\"private_key\": \"" + System.getenv("FIREBASE_PRIVATE_KEY").replace("\\n", "\n") + "\","
                    + "\"client_email\": \"" + System.getenv("FIREBASE_CLIENT_EMAIL") + "\","
                    + "\"client_id\": \"" + System.getenv("FIREBASE_CLIENT_ID") + "\","
                    + "\"auth_uri\": \"" + System.getenv("FIREBASE_AUTH_URI") + "\","
                    + "\"token_uri\": \"" + System.getenv("FIREBASE_TOKEN_URI") + "\","
                    + "\"auth_provider_x509_cert_url\": \"" + System.getenv("FIREBASE_AUTH_PROVIDER_X509_CERT_URL") + "\","
                    + "\"client_x509_cert_url\": \"" + System.getenv("FIREBASE_CLIENT_X509_CERT_URL") + "\""
                    + "}";

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(
                            new ByteArrayInputStream(firebaseConfig.getBytes(StandardCharsets.UTF_8))))
                    .build();
            FirebaseApp.initializeApp(options);
            System.out.println("FirebaseApp initialized successfully.");
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("Failed to initialize FirebaseApp.");
        }
    }
}

//    @PostConstruct
//    public void initialize() {
//        try {
//            FirebaseOptions options = FirebaseOptions.builder()
//                    .setCredentials(GoogleCredentials.fromStream(new ClassPathResource("sitprep-new-firebase-adminsdk.json").getInputStream()))
//                    .build();
//            FirebaseApp.initializeApp(options);
//            System.out.println("FirebaseApp initialized successfully.");
//        } catch (IOException e) {
//            e.printStackTrace();
//            System.err.println("Failed to initialize FirebaseApp.");
//        }
//    }
//}
