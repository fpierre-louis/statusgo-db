// src/main/java/io/sitprep/sitprepapi/config/SecurityConfig.java
package io.sitprep.sitprepapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Enable CORS (uses the bean below)
                .cors(Customizer.withDefaults())
                // Disable CSRF for a stateless API (no sessions)
                .csrf(csrf -> csrf.disable())
                // TEMP: open API + WebSocket endpoints while JWT is removed
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/ws/**", "/app/**", "/topic/**").permitAll()
                        .requestMatchers("/api/**").permitAll()  // <— open for now
                        .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .anyRequest().permitAll()
                );

        // NOTE: no JWT filter here anymore.
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
                "https://statusnow.app",
                "https://www.statusnow.app",
                "https://www.sitprep.app",
                "https://sitprep.app"
        ));

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
