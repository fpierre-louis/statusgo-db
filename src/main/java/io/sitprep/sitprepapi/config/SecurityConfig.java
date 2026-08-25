// src/main/java/io/sitprep/sitprepapi/config/SecurityConfig.java
package io.sitprep.sitprepapi.config;

import io.sitprep.sitprepapi.security.FirebaseAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final FirebaseAuthFilter firebaseAuthFilter;

    public SecurityConfig(FirebaseAuthFilter firebaseAuthFilter) {
        this.firebaseAuthFilter = firebaseAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Enable CORS (uses the bean below)
                .cors(Customizer.withDefaults())
                // Disable CSRF for a stateless API (no sessions)
                .csrf(csrf -> csrf.disable())
                // /api/** REQUIRES A VERIFIED TOKEN (A9, 2026-08-24).
                //
                // It used to be permitAll, alongside a filter that verifies a token
                // when present and never rejects. That combination meant a missing
                // authorization call in a controller was not a weaker gate — it was
                // NO gate, and the endpoint worked. The 2026-08-24 authorization
                // audit found 21 endpoints in that state across four classes, every
                // one of them a forgotten line rather than a decision. Fixing the
                // instances (A1-A8) does not stop the next one; closing the default
                // does. Forgetting a gate now produces a 401, not a leak.
                //
                // THE ALLOWLIST BELOW IS THE WHOLE RISK OF THIS CHANGE. A route that
                // legitimately serves people without an account, and is not named
                // here, starts answering 401 the moment this ships — and several are
                // the flows a stranger hits FIRST. Every entry is evidence-backed;
                // the reasoning per row is in
                // docs/audits/2026-08-24-public-route-allowlist.md, and
                // SecurityConfigAllowlistTest exercises them over the real chain.
                //
                // Deliberately NOT allowlisted, each because the evidence said so:
                // geocode proxies (no pre-auth caller, and an open relay to Nominatim
                // is worse than a closed one), /api/alerts reads (the pre-auth path
                // goes direct to NWS via skipBackendCache), /api/config/defaults
                // (no public caller; and its client falls back to bundled constants
                // that are numerically identical, so being wrong costs nothing),
                // /api/verified-publishers (both consumers are behind ProtectedRoute),
                // /api/alert-mode (built, not wired to anything), and
                // POST /api/userinfo/firebase — which ALREADY requires a token via
                // requireAuthenticatedUid, proven by ColdStartSignupTest rather than
                // assumed, because getting that one wrong means nobody can sign up.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/ws/**", "/app/**", "/topic/**").permitAll()

                        // Ghost-tenant opt-out links are clicked by non-registered
                        // recipients who carry no Firebase token. Security comes from
                        // the HMAC-signed outreach token in the URL, not the session.
                        .requestMatchers("/api/public/**").permitAll()

                        // Recipient activation. A shared evacuation plan is opened by
                        // people who may have no SitPrep account at all — requiring a
                        // token here means someone in an emergency cannot tell their
                        // family they are safe. /{id} is auth-optional by design
                        // (owner gets the full snapshot, a link holder gets the
                        // data-minimized view). Note the acks split: POST is the
                        // recipient checking in and is public; GET is the owner's
                        // roll-up of everyone's coordinates and is not.
                        .requestMatchers(HttpMethod.GET, "/api/plans/activations/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/plans/activations/*/map-public").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/plans/activations/*/acks").permitAll()

                        // Invite links: the recipient lands here before signing in.
                        // The DTO is sanitized (no member or admin emails) precisely
                        // so it can be served this way.
                        .requestMatchers(HttpMethod.GET, "/api/groups/*/preview").permitAll()

                        // Stripe will never send a Firebase token. The
                        // Stripe-Signature HMAC is the authentication. A 401 here is
                        // silent billing failure with retries.
                        .requestMatchers(HttpMethod.POST, "/api/billing/webhook").permitAll()

                        // Ask reads are anonymous by a locked 2026-05-04 product
                        // decision, and the FE agrees: /ask, /ask/q/:id and
                        // /ask/tips/:id render without ProtectedRoute while the
                        // compose routes are wrapped. Listed per-path, NOT as
                        // /api/ask/** — that would also open /api/ask/bookmarks,
                        // which is per-user and must stay authenticated.
                        .requestMatchers(HttpMethod.GET, "/api/ask/questions").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/ask/questions/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/ask/tips").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/ask/tips/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/ask/search").permitAll()

                        // Guest map browsing — auth-optional by the resource's own
                        // contract (a signed-in viewer additionally gets viewerRole).
                        .requestMatchers(HttpMethod.GET, "/api/community/map").permitAll()

                        // Public marketing surfaces, each with a public FE route:
                        // /emergency-supplies (affiliate reviewers must reach it
                        // logged out), /sitprep-quiz, and /claim-agency.
                        .requestMatchers(HttpMethod.GET, "/api/retail/products").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/readiness/assessment/questions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/readiness/assessment/evaluate").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/agency/requests").permitAll()

                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .anyRequest().permitAll()
                )
                // 401, not Spring's default 403, for an unauthenticated caller.
                // AuthUtils.requireAuthenticatedEmail has always answered 401, and
                // the frontend branches on it; a matcher that answered 403 for the
                // same condition would be a second dialect of "you are not signed
                // in" for clients to learn.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(firebaseAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();

        // FIX FOR WEBSOCKET/SOCKJS: Set AllowCredentials to true.
        // This is necessary because the SockJS client demands the header,
        // and Spring in this version does not let us set it specifically on the /ws endpoint.
        cfg.setAllowedOrigins(List.of(
                "http://localhost:3000",
                "http://localhost:4200",
                "https://statusgo-db-0889387bb209.herokuapp.com",
                "https://www.sitprep.app",
                "https://sitprep.app",
                "https://rediscover-sports.netlify.app",
                // Capacitor iOS — the WKWebView loads the FE bundle from
                // `capacitor://localhost`; every fetch from the iOS app
                // carries that as the Origin header. Without this entry,
                // CORS preflights from the iOS app 403 silently and the
                // app appears completely broken with no API connectivity.
                "capacitor://localhost",
                // Capacitor Android — when androidScheme=https (set in
                // capacitor.config.ts), the WebView loads from
                // https://localhost. Same CORS implications.
                "https://localhost",
                // Local dev (Vite dev server / API tooling). No trailing
                // slash — Spring matches the Origin header scheme+host+port
                // exactly, so "http://localhost:8080/" would never match.
                "http://localhost:8080"
        ));
        // Note: statusnow.app removed 2026-05-04 — it 301s to sitprep.app
        // at the Netlify edge, so the browser follows the redirect and
        // makes API calls from the sitprep.app origin. statusnow.app
        // never appears as a CORS origin against this BE in practice.

        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));

        // *** THE CRITICAL CHANGE ***
        cfg.setAllowCredentials(true); // <-- MUST BE TRUE to fix SockJS CORS error

        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
