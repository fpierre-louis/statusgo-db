package io.sitprep.sitprepapi.constant;

/**
 * Feature-level permissions for SitPrep's internal platform console.
 *
 * <p>Mirrored by the frontend platformRoles.js file for UI gating only;
 * backend resources remain authoritative and check these permissions per call.</p>
 */
public enum PlatformPermission {
    VIEW_CONSOLE,
    REVIEW_AGENCY_REQUESTS,
    PROVISION_AGENCY,
    GRANT_AUTHORITY_STAMP,
    MODERATE_REPORTS,
    MANAGE_PUBLISHERS,
    MANAGE_BILLING,
    VIEW_METRICS,
    MANAGE_ADMINS,
    VIEW_PII
}
