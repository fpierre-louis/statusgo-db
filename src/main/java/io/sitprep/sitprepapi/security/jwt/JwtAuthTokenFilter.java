package io.sitprep.sitprepapi.security.jwt;

import com.auth0.jwt.interfaces.DecodedJWT; // <-- Import this
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthTokenFilter extends OncePerRequestFilter {

    // ✅ INJECT THE NEW VERIFIER
    private final FirebaseTokenVerifier tokenVerifier;

    @Autowired
    public JwtAuthTokenFilter(FirebaseTokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = parseJwt(request);
            if (jwt != null) {
                // ✅ USE THE NEW VERIFIER
                DecodedJWT decodedJWT = tokenVerifier.verifyToken(jwt);
                String email = decodedJWT.getClaim("email").asString();

                System.out.println("✅ Local JWT verification successful. Email: " + email);

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        email, null, Collections.emptyList());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception e) {
            // Log the error but allow the request to continue (it will be unauthenticated)
            logger.error("❌ Invalid JWT Token: " + e.getMessage());
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");
        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }

        // This part is still needed for WebSocket connections
        String token = request.getParameter("access_token");
        if (StringUtils.hasText(token)) {
            return token;
        }

        return null;
    }
}