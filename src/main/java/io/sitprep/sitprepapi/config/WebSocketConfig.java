package io.sitprep.sitprepapi.config;

import io.sitprep.sitprepapi.security.jwt.JwtUtils;
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

import java.util.Collections;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtUtils jwtUtils;
    private final AccessTokenHandshakeInterceptor accessTokenHandshakeInterceptor;

    public WebSocketConfig(JwtUtils jwtUtils,
                           AccessTokenHandshakeInterceptor accessTokenHandshakeInterceptor) {
        this.jwtUtils = jwtUtils;
        this.accessTokenHandshakeInterceptor = accessTokenHandshakeInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(
                        "https://sitprep.app",
                        "https://www.sitprep.app",
                        "https://*.netlify.app",
                        "http://localhost:*"
                )
                .addInterceptors(accessTokenHandshakeInterceptor);
        // If you later want SockJS fallback:
        // .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String bearer = null;

                    // 1) Try STOMP CONNECT header: Authorization: Bearer <token>
                    String authHeader = accessor.getFirstNativeHeader("Authorization");
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        bearer = authHeader.substring(7);
                    }

                    // 2) Fallback: token captured during handshake as session attribute "access_token"
                    if (bearer == null && accessor.getSessionAttributes() != null) {
                        Object t = accessor.getSessionAttributes().get("access_token");
                        if (t instanceof String s && !s.isBlank()) {
                            bearer = s;
                        }
                    }

                    if (bearer != null && jwtUtils.validateJwtToken(bearer)) {
                        String email = jwtUtils.getUserEmailFromJwtToken(bearer);
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList());

                        accessor.setUser(authentication);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        System.out.println("✅ WebSocket authenticated: " + email);
                    } else {
                        System.err.println("❌ WS CONNECT: missing/invalid token (header or handshake)");
                    }
                }

                if (accessor != null && accessor.getUser() != null) {
                    SecurityContextHolder.getContext().setAuthentication(
                            (UsernamePasswordAuthenticationToken) accessor.getUser()
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
