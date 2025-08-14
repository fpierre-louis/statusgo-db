package io.sitprep.sitprepapi.websocket;

import io.sitprep.sitprepapi.security.jwt.JwtUtils;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.Map;

@Component
public class JwtHandshakeHandler extends DefaultHandshakeHandler {

    private final JwtUtils jwtUtils;

    public JwtHandshakeHandler(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @Override
    protected Principal determineUser(ServerHttpRequest request,
                                      WebSocketHandler wsHandler,
                                      Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletReq) {
            HttpServletRequest http = servletReq.getServletRequest();

            // 1) access_token query param (SockJS-friendly)
            String token = http.getParameter("access_token");

            // 2) or Authorization: Bearer <token>
            if ((token == null || token.isBlank())) {
                String auth = http.getHeader("Authorization");
                if (auth != null && auth.startsWith("Bearer ")) {
                    token = auth.substring(7);
                }
            }

            if (token != null && jwtUtils.validateJwtToken(token)) {
                String email = jwtUtils.getUserEmailFromJwtToken(token);
                if (email != null && !email.isBlank()) {
                    return new StompPrincipal(email);
                }
            }
        }
        // returning null means anonymous; your handlers already guard for that
        return null;
    }
}
