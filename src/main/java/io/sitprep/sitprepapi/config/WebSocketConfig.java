package io.sitprep.sitprepapi.config;

import io.sitprep.sitprepapi.security.jwt.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;
import java.util.Collections;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    private JwtUtils jwtUtils;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(
                        "http://localhost:3000",
                        "http://localhost:4200",
                        "https://statusgo-db-0889387bb209.herokuapp.com",
                        "https://statusnow.app",
                        "https://www.statusnow.app",
                        "https://www.sitprep.app",
                        "https://sitprep.app"
                );
        // Do not use .withSockJS() unless you need legacy fallback
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String authHeader = accessor.getFirstNativeHeader("Authorization");

                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        String token = authHeader.substring(7);
                        if (jwtUtils.validateJwtToken(token)) {
                            String email = jwtUtils.getUserEmailFromJwtToken(token);
                            UsernamePasswordAuthenticationToken authentication =
                                    new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList());

                            accessor.setUser(authentication);
                            SecurityContextHolder.getContext().setAuthentication(authentication);

                            System.out.println("✅ WebSocket authenticated: " + email);
                        } else {
                            System.err.println("❌ Invalid JWT during STOMP CONNECT");
                        }
                    } else {
                        System.err.println("⚠️ Missing Authorization header in STOMP CONNECT");
                    }
                }

                Principal user = accessor != null ? accessor.getUser() : null;
                if (user != null) {
                    SecurityContextHolder.getContext().setAuthentication(
                            (UsernamePasswordAuthenticationToken) user
                    );
                }

                return message;
            }

            @Override
            public void postSend(Message<?> message, MessageChannel channel, boolean sent) {
                SecurityContextHolder.clearContext();
            }
        });
    }
}
