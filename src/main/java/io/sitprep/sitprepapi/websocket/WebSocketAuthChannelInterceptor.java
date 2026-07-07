package io.sitprep.sitprepapi.websocket;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import io.sitprep.sitprepapi.service.PlanActivationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.Principal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    /**
     * Per-activation ack stream — carries recipient names, statuses, and live
     * coordinates. The activationId rides in the share link, so link holders
     * know the topic name; SUBSCRIBE must therefore be authorized per session
     * (owner / household / targeted member), mirroring the SEC-3 gate on
     * {@code GET /{id}/acks}. Other activation topics (plan-update frames)
     * carry no PII and keep the link-possession contract.
     */
    private static final Pattern ACTIVATION_ACKS_TOPIC =
            Pattern.compile("^/topic/activations/([^/]+)/acks$");

    /**
     * Lazy provider, not a direct dependency: the service graph reaches
     * SimpMessagingTemplate (via WebSocketMessageSender), which the broker
     * config that registers this interceptor is itself constructing.
     */
    private final ObjectProvider<PlanActivationService> planActivationService;

    public WebSocketAuthChannelInterceptor(ObjectProvider<PlanActivationService> planActivationService) {
        this.planActivationService = planActivationService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            authenticateConnect(accessor);
            return message;
        }
        if (command == StompCommand.SUBSCRIBE) {
            authorizeSubscribe(accessor);
        }
        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String token = extractToken(accessor);
        if (!StringUtils.hasText(token)) {
            throw new IllegalArgumentException("Authenticated WebSocket CONNECT required");
        }

        try {
            FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(token);
            String email = decoded.getEmail();
            if (!StringUtils.hasText(email)) {
                throw new IllegalArgumentException("Firebase token has no email");
            }
            String normalizedEmail = email.trim().toLowerCase();
            accessor.setUser(new StompPrincipal(normalizedEmail));
            if (accessor.getSessionAttributes() != null) {
                accessor.getSessionAttributes().put("email", normalizedEmail);
                accessor.getSessionAttributes().put("firebaseUid", decoded.getUid());
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid WebSocket auth token", e);
        }
    }

    /**
     * SUBSCRIBE authorization (2026-07-07): CONNECT-only auth left every
     * broker topic open to any authenticated session, so anyone a recipient
     * forwarded a share link to could stream the household's live check-in
     * coordinates. Destinations matching the acks topic now require the
     * session identity to pass the activation's reader authorization.
     */
    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (!StringUtils.hasText(destination)) {
            return;
        }
        Matcher m = ACTIVATION_ACKS_TOPIC.matcher(destination);
        if (!m.matches()) {
            return;
        }

        String email = sessionEmail(accessor);
        if (!StringUtils.hasText(email)) {
            throw new IllegalArgumentException(
                    "Authenticated session required to subscribe to " + destination);
        }
        PlanActivationService service = planActivationService.getObject();
        if (!service.canReadActivationAcks(m.group(1), email)) {
            throw new IllegalArgumentException(
                    "Not authorized to subscribe to " + destination);
        }
    }

    /** Session identity set at CONNECT — principal first, session attribute fallback. */
    private static String sessionEmail(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user != null && StringUtils.hasText(user.getName())) {
            return user.getName();
        }
        Map<String, Object> attrs = accessor.getSessionAttributes();
        Object email = attrs != null ? attrs.get("email") : null;
        return (email instanceof String s && StringUtils.hasText(s)) ? s : null;
    }

    private static String extractToken(StompHeaderAccessor accessor) {
        String auth = accessor.getFirstNativeHeader("Authorization");
        if (StringUtils.hasText(auth) && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        String lowerAuth = accessor.getFirstNativeHeader("authorization");
        if (StringUtils.hasText(lowerAuth) && lowerAuth.startsWith("Bearer ")) {
            return lowerAuth.substring(7).trim();
        }
        String firebaseToken = accessor.getFirstNativeHeader("firebaseToken");
        if (StringUtils.hasText(firebaseToken)) return firebaseToken.trim();
        String accessToken = accessor.getFirstNativeHeader("access_token");
        return StringUtils.hasText(accessToken) ? accessToken.trim() : null;
    }
}
