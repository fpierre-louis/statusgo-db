package io.sitprep.sitprepapi.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@Profile("production")
public class ProductionWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtHandshakeHandler jwtHandshakeHandler;

    public ProductionWebSocketConfig(JwtHandshakeHandler jwtHandshakeHandler) {
        this.jwtHandshakeHandler = jwtHandshakeHandler;
    }

    @Value("${STOMP_HOST}")       private String relayHost;
    @Value("${STOMP_PORT:61613}") private Integer relayPort;
    @Value("${STOMP_USER}")       private String relayUser;
    @Value("${STOMP_PASS}")       private String relayPass;
    @Value("${STOMP_VHOST:/}")    private String relayVhost;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setHandshakeHandler(jwtHandshakeHandler)
                .setAllowedOriginPatterns(
                        "http://localhost:*",
                        "https://sitprep.app",
                        "https://www.sitprep.app",
                        "https://statusnow.app",
                        "https://www.statusnow.app",
                        "https://statusgo-db-0889387bb209.herokuapp.com"
                )
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");

        // ⚠️ Do NOT relay "/user" – Spring resolves /user destinations locally per session.
        registry.enableStompBrokerRelay("/topic", "/queue")
                .setRelayHost(relayHost)
                .setRelayPort(relayPort)
                .setClientLogin(relayUser)
                .setClientPasscode(relayPass)
                .setSystemLogin(relayUser)
                .setSystemPasscode(relayPass)
                .setVirtualHost(relayVhost)
                .setSystemHeartbeatSendInterval(15000)
                .setSystemHeartbeatReceiveInterval(15000);
    }
}
