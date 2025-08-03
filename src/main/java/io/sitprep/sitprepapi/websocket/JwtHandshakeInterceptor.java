package io.sitprep.sitprepapi.websocket;

import io.sitprep.sitprepapi.security.jwt.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.Principal;
import java.util.Map;

@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    @Autowired
    private JwtUtils jwtUtils;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String query = request.getURI().getQuery();

        if (query != null && query.contains("token")) {
            Map<String, String> params = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().toSingleValueMap();
            String token = params.get("token");

            if (jwtUtils.validateJwtToken(token)) {
                String email = jwtUtils.getUserEmailFromJwtToken(token);
                attributes.put("user", (Principal) () -> email);
                System.out.println("✅ WebSocket Authenticated as: " + email);
            } else {
                System.err.println("❌ Invalid JWT in handshake");
            }
        } else {
            System.err.println("❌ Missing token in WebSocket handshake");
        }

        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
