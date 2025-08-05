// OwnershipValidator.java
package io.sitprep.sitprepapi.util;

public class OwnershipValidator {

    public static void requireOwnerEmailMatch(String resourceOwnerEmail) {
        String currentUserEmail = AuthUtils.getCurrentUserEmail();
        if (!currentUserEmail.equalsIgnoreCase(resourceOwnerEmail)) {
            throw new SecurityException("Unauthorized: You do not own this resource.");
        }
    }
}
