package io.sitprep.sitprepapi.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
public class WebSocketAuthConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthChannelInterceptor authInterceptor;

    public WebSocketAuthConfig(WebSocketAuthChannelInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
