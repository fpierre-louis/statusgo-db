package io.sitprep.sitprepapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // Disable CSRF for now to allow easier WebSocket testing
                .cors(cors -> cors.configurationSource(corsConfigurationSource())) // Use the unified CORS bean
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/ws/**").permitAll() // Allow WebSocket connections without security
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll() // Allow OPTIONS for CORS pre-flight
                        .anyRequest().authenticated() // All other requests must be authenticated
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Use the comprehensive list of origins
        configuration.setAllowedOrigins(List.of("http://localhost:3000", "http://localhost:4200", "https://statusgo-db-0889387bb209.herokuapp.com", "https://statusnow.app", "https://www.statusnow.app", "https://www.sitprep.app", "https://sitprep.app"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*")); // Allow all headers
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    // You can now delete the separate CorsConfig.java file
    // as its logic has been merged here.
}