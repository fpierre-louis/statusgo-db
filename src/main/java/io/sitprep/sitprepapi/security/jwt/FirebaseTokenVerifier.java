package io.sitprep.sitprepapi.security.jwt;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.TimeUnit;

@Service
public class FirebaseTokenVerifier {

    private final JwkProvider jwkProvider;
    private final String firebaseProjectId;

    /**
     * Constructor that sets up the provider to fetch and cache Firebase's public keys.
     */
    public FirebaseTokenVerifier(@Value("${firebase.project-id}") String firebaseProjectId) throws Exception {
        this.firebaseProjectId = firebaseProjectId;
        URL jwksUrl = new URL("https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com");

        // This provider caches the keys for 10 hours by default
        this.jwkProvider = new UrlJwkProvider(jwksUrl);
    }

    public DecodedJWT verifyToken(String token) throws Exception {
        // 1. Decode the token to get the Key ID (kid) from the header
        DecodedJWT decodedJwt = JWT.decode(token);
        String keyId = decodedJwt.getKeyId();

        // 2. Fetch the corresponding public key from the cache (or refresh if needed)
        Jwk jwk = jwkProvider.get(keyId);
        RSAPublicKey publicKey = (RSAPublicKey) jwk.getPublicKey();

        // 3. Create the RSA256 algorithm instance
        Algorithm algorithm = Algorithm.RSA256(publicKey, null);

        // 4. Build the verifier with the correct algorithm and claims
        String expectedIssuer = "https://securetoken.google.com/" + firebaseProjectId;

        return JWT.require(algorithm)
                .withIssuer(expectedIssuer)
                .withAudience(firebaseProjectId)
                .build()
                .verify(token); // This throws an exception if the token is invalid
    }
}