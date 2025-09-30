//package io.sitprep.sitprepapi.security.jwt;
//
//import com.google.firebase.auth.FirebaseToken; // Import FirebaseToken
//import jakarta.servlet.FilterChain;
//import jakarta.servlet.ServletException;
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
//import org.springframework.stereotype.Component;
//import org.springframework.util.StringUtils;
//import org.springframework.web.filter.OncePerRequestFilter;
//
//import java.io.IOException;
//import java.util.Collections;
//
//@Component
//public class JwtAuthTokenFilter extends OncePerRequestFilter {
//
//    // ✅ INJECT THE CORRECT UTILITY CLASS
//    private final JwtUtils jwtUtils;
//
//    @Autowired
//    public JwtAuthTokenFilter(JwtUtils jwtUtils) {
//        this.jwtUtils = jwtUtils;
//    }
//
//    @Override
//    protected void doFilterInternal(HttpServletRequest request,
//                                    HttpServletResponse response,
//                                    FilterChain filterChain) throws ServletException, IOException {
//        try {
//            String jwt = parseJwt(request);
//            // ✅ USE THE CORRECT VALIDATION METHOD
//            if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
//                String email = jwtUtils.getUserEmailFromJwtToken(jwt);
//
//                if (email != null) {
//                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
//                            email, null, Collections.emptyList());
//                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
//
//                    SecurityContextHolder.getContext().setAuthentication(authentication);
//                }
//            }
//        } catch (Exception e) {
//            logger.error("Cannot set user authentication: {}", e);
//        }
//
//        filterChain.doFilter(request, response);
//    }
//
//    private String parseJwt(HttpServletRequest request) {
//        String headerAuth = request.getHeader("Authorization");
//        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
//            return headerAuth.substring(7);
//        }
//
//        // Fallback for WebSocket connections that pass the token as a query parameter
//        String token = request.getParameter("access_token");
//        if (StringUtils.hasText(token)) {
//            return token;
//        }
//
//        return null;
//    }
//}