// src/main/java/io/sitprep/sitprepapi/util/OwnershipValidator.java
package io.sitprep.sitprepapi.util;

public final class OwnershipValidator {
    private OwnershipValidator() {}

    /** MVP: disable strict ownership checks. */
    public static void requireOwnerEmailMatch(String resourceOwnerEmail) {
        // No-op in MVP — re-enable when auth is back.
    }
}
