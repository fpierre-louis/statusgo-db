package io.sitprep.sitprepapi.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import io.sitprep.sitprepapi.service.LastActivityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * Verifies a Firebase ID token from {@code Authorization: Bearer ...} (or the
 * {@code access_token} query param, for SockJS handshakes which can't set
 * custom headers) and populates Spring's {@link SecurityContextHolder} with
 * the caller's email as principal + Firebase UID in {@link FirebaseAuthenticationDetails}.
 *
 * <p><b>Verify-but-don't-enforce.</b> On missing or invalid tokens this filter
 * simply leaves {@code SecurityContext} empty and lets the request through —
 * it's meant to run alongside the existing {@code .permitAll()} rules and the
 * per-endpoint {@code authorEmail}/{@code actor} body-param fallbacks. When
 * we're ready to enforce, tighten {@code SecurityConfig} to require
 * {@code .authenticated()} on the relevant matchers; this filter stays as-is.
 */
@Component
public class FirebaseAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthFilter.class);

    /** Setter-injected to avoid a constructor cycle with services that import this class indirectly. */
    private LastActivityService lastActivityService;

    @Autowired
    public void setLastActivityService(LastActivityService lastActivityService) {
        this.lastActivityService = lastActivityService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(token);
                String email = decoded.getEmail();
                String uid = decoded.getUid();
                if (email != null && !email.isBlank()) {
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            email.toLowerCase(), null, Collections.emptyList());
                    auth.setDetails(new FirebaseAuthenticationDetails(request, uid));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    // Bump UserInfo.lastActiveAt for presence — throttled to
                    // ~5 min/user inside the service so write pressure is bounded.
                    if (lastActivityService != null) lastActivityService.touch(email);
                }
            } catch (FirebaseAuthException e) {
                // Invalid / expired / revoked token — don't reject, just log
                // and let the request proceed anonymously. The target endpoint
                // will fall back to its body-param actor for now.
                log.debug("Firebase token rejected: {}", e.getMessage());
            } catch (Exception e) {
                // Anything unexpected (network, SDK not initialized yet) — same
                // stance: warn but don't break the request.
                log.warn("Firebase token verification failed: {}", e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String raw = header.substring(7).trim();
            return raw.isEmpty() ? null : raw;
        }
        // SockJS / STOMP handshake fallback — client appends ?access_token=...
        // on the /ws endpoint because SockJS can't add custom headers.
        String fromQuery = request.getParameter("access_token");
        return StringUtils.hasText(fromQuery) ? fromQuery : null;
    }
}
