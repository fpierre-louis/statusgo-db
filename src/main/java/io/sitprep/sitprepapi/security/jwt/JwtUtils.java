//package io.sitprep.sitprepapi.security.jwt;
//
//import com.google.firebase.auth.FirebaseAuth;
//import com.google.firebase.auth.FirebaseToken;
//import org.springframework.stereotype.Component;
//
//@Component
//public class JwtUtils {
//
//    public boolean validateJwtToken(String authToken) {
//        try {
//            FirebaseAuth.getInstance().verifyIdToken(authToken);
//            return true;
//        } catch (Exception e) {
//            System.err.println("Invalid Firebase JWT: " + e.getMessage());
//            return false;
//        }
//    }
//
//    public String getUserEmailFromJwtToken(String token) {
//        try {
//            FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(token);
//            return decoded.getEmail();
//        } catch (Exception e) {
//            System.err.println("Failed to decode Firebase JWT: " + e.getMessage());
//            return null;
//        }
//    }
//
//
//}
