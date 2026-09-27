package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.AgencyCapability;
import io.sitprep.sitprepapi.domain.Group;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgencyCapabilityAuthorizationTest {

    private final AgencyAuthorizationService auth =
            new AgencyAuthorizationService(null, null, null);

    @Test
    void approvedAgencyWithoutAreaAlertGrantCannotPost() {
        Group agency = agency();
        auth.applyApprovedCapabilities(agency, false);

        assertThatThrownBy(() -> auth.requireAgencyPostingAllowed(agency, "admin@city.gov"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("SEND_AREA_ALERTS");
    }

    @Test
    void approvedAgencyWithAreaAlertGrantAndRadiusCanPost() {
        Group agency = agency();
        agency.setJurisdictionLat(40.39);
        agency.setJurisdictionLng(-111.85);
        agency.setJurisdictionRadiusMiles(12.0);
        auth.applyApprovedCapabilities(agency, true);

        auth.requireAgencyPostingAllowed(agency, "admin@city.gov");

        assertThat(agency.getAgencyCapabilities()).contains(
                AgencyCapability.OPERATE_CIVIC_QUEUE,
                AgencyCapability.MANAGE_WORK,
                AgencyCapability.MANAGE_STAFF,
                AgencyCapability.SEND_AREA_ALERTS);
    }

    @Test
    void zipOnlyAgencyIsJurisdictionReady() {
        Group agency = agency();
        agency.setJurisdictionZips(List.of("84043"));

        assertThat(AgencyAuthorizationService.isJurisdictionReady(agency)).isTrue();
    }

    @Test
    void radiusOnlyAgencyIsJurisdictionReady() {
        Group agency = agency();
        agency.setJurisdictionLat(40.39);
        agency.setJurisdictionLng(-111.85);
        agency.setJurisdictionRadiusMiles(12.0);

        assertThat(AgencyAuthorizationService.isJurisdictionReady(agency)).isTrue();
    }

    @Test
    void approvalWithoutEmergencyPostingRevokesAnOldAlertGrant() {
        Group agency = agency();
        agency.setAgencyCapabilities(new LinkedHashSet<>(Set.of(AgencyCapability.SEND_AREA_ALERTS)));

        auth.applyApprovedCapabilities(agency, false);

        assertThat(agency.getAgencyCapabilities())
                .doesNotContain(AgencyCapability.SEND_AREA_ALERTS)
                .contains(AgencyCapability.OPERATE_CIVIC_QUEUE);
    }

    @Test
    void viewerPermissionsAreDerivedFromRoleAndOrganizationCapabilities() {
        Group agency = agency();
        auth.applyApprovedCapabilities(agency, true);

        assertThat(AgencyAuthorizationService.viewerPermissions(agency, "admin@city.gov"))
                .contains(AgencyCapability.SEND_AREA_ALERTS);
        assertThat(AgencyAuthorizationService.viewerPermissions(agency, "member@example.com"))
                .isEmpty();
    }

    private static Group agency() {
        Group group = new Group();
        group.setGroupId("g-city");
        group.setOwnerEmail("owner@city.gov");
        group.setAdminEmails(List.of("admin@city.gov"));
        group.setMemberEmails(List.of("member@example.com"));
        return group;
    }
}
