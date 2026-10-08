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
    VIEW_PII,
    /**
     * Enable / disable safety-reviewed Practice content (the kill switch at
     * /api/admin/practice/content). Deliberately NOT in any role's defaults
     * but SUPER_ADMIN's: moderating a reported comment is not authority over
     * life-safety educational content (owner review 2026-10-08). Grant it
     * explicitly as an extra grant.
     */
    MANAGE_PRACTICE_CONTENT
}
