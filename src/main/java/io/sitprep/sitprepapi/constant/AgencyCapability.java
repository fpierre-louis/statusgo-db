package io.sitprep.sitprepapi.constant;

/**
 * Server-owned capabilities granted to an authorized agency group.
 *
 * <p>The broad {@code Group.agencyAuthorized} stamp establishes that the
 * organization is an agency. It does not, by itself, authorize every agency
 * operation. In particular, area-wide alert delivery is a separately reviewed
 * capability.</p>
 */
public enum AgencyCapability {
    OPERATE_CIVIC_QUEUE,
    MANAGE_WORK,
    SEND_AREA_ALERTS,
    MANAGE_STAFF
}
