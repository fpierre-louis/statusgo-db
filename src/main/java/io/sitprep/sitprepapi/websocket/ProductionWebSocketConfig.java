package io.sitprep.sitprepapi.websocket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@Profile("production") // Active ONLY when the 'production' profile is active
public class ProductionWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    private JwtHandshakeHandler jwtHandshakeHandler;

    @Value("${spring.rabbitmq.host}")
    private String relayHost;

    @Value("${spring.rabbitmq.username}")
    private String relayUser;

    @Value("${spring.rabbitmq.password}")
    private String relayPass;

    @Value("${spring.rabbitmq.stomp-port:61613}")
    private int relayPort;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");

        registry.enableStompBrokerRelay("/topic", "/queue")
                .setRelayHost(this.relayHost)
                .setRelayPort(this.relayPort)
                .setClientLogin(this.relayUser)
                .setClientPasscode(this.relayPass)
                .setSystemLogin(this.relayUser)
                .setSystemPasscode(this.relayPass);

        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setHandshakeHandler(jwtHandshakeHandler)
                .setAllowedOrigins(
                        "http://localhost:3000",
                        "http://localhost:4200",
                        "https://statusgo-db-0889387bb209.herokuapp.com",
                        "https://statusnow.app",
                        "https://www.statusnow.app",
                        "https://www.sitprep.app",
                        "https://sitprep.app"
                )
                .withSockJS();
    }
}