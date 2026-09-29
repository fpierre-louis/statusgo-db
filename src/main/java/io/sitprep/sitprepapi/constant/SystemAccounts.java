package io.sitprep.sitprepapi.constant;

import java.util.Locale;

/**
 * Accounts the server itself posts as. One place, so the dispatcher that
 * writes as SitPrep and the DTO that labels SitPrep's posts can't disagree.
 */
public final class SystemAccounts {

    /** SitPrep's own auto-posts (AlertDispatchService). */
    public static final String SITPREP_EMAIL = "system@sitprep.app";

    /** The author type SitPrep's posts carry, so clients draw the SitPrep mark. */
    public static final String SITPREP_AUTHOR_TYPE = "sitprep";

    private SystemAccounts() {}

    public static boolean isSitPrep(String email) {
        return email != null && SITPREP_EMAIL.equals(email.trim().toLowerCase(Locale.ROOT));
    }
}
