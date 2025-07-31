package io.sitprep.sitprepapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker // Enables WebSocket message handling, backed by a message broker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable a simple in-memory message broker.
        // Messages destined for "/topic" or "/queue" will be routed to connected clients.
        config.enableSimpleBroker("/topic", "/queue");

        // Set the prefix for application-level messages.
        // Messages from clients to the server should be prefixed with "/app" (e.g., /app/send-message).
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Register the WebSocket endpoint. Clients will connect to "/ws".
        // `setAllowedOriginPatterns("*")` allows connections from any origin (for development).
        // For production, this should be restricted to your frontend domain (e.g., "https://yoursitprepapp.com").
        // `withSockJS()` enables SockJS fallback options for browsers that don't support native WebSockets.
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }
}