package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AgencyStaff;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.AgencyStaffRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec for the STAFF read gate + the server-side staff-email guard.
 *
 * <p>Both are security-relevant: {@code requireAgencyStaffOrAdmin} is what lets
 * a non-admin employee read an agency's civic queue (resident reports carrying
 * reporter name, neighborhood, description, photo), and {@code add()} is the
 * privilege GRANT that creates that access on a column with no FK to
 * {@code user_info}. Pure Mockito — no Spring context, no DB.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgencyStaffAuthorizationTest {

    @Mock AgencyStaffRepo staffRepo;

    private AgencyStaffService staffService() {
        return new AgencyStaffService(staffRepo);
    }

    private AgencyAuthorizationService auth() {
        // userInfoRepo / userGeoService are untouched by the gate under test.
        return new AgencyAuthorizationService(null, null, staffService());
    }

    private static Group agency(boolean authorized) {
        Group g = new Group();
        g.setGroupId("g-1");
        g.setAgencyAuthorized(authorized);
        g.setOwnerEmail("owner@city.gov");
        g.setAdminEmails(List.of("admin@city.gov"));
        g.setMemberEmails(List.of("resident@example.com"));
        return g;
    }

    // ── the gate ────────────────────────────────────────────────────────────

    @Test
    void admin_passes_withoutEvenQueryingTheStaffTable() {
        auth().requireAgencyStaffOrAdmin(agency(true), "admin@city.gov");
        // Short-circuit matters: the admin path must not pay for a staff lookup.
        verify(staffRepo, never()).existsByGroupIdAndUserEmailIgnoreCase(anyString(), anyString());
    }

    @Test
    void owner_passes() {
        auth().requireAgencyStaffOrAdmin(agency(true), "owner@city.gov");
    }

    @Test
    void staffOnly_passes_evenThoughGroupRoleIsNone() {
        // The whole point of STAFF: this person is in no email list at all.
        when(staffRepo.existsByGroupIdAndUserEmailIgnoreCase("g-1", "crew@city.gov")).thenReturn(true);
        auth().requireAgencyStaffOrAdmin(agency(true), "crew@city.gov");
    }

    @Test
    void staffMemberWhoIsAlsoAPlainMember_passes() {
        // Staff is INDEPENDENT of group role — being MEMBER must not block it.
        when(staffRepo.existsByGroupIdAndUserEmailIgnoreCase("g-1", "resident@example.com")).thenReturn(true);
        auth().requireAgencyStaffOrAdmin(agency(true), "resident@example.com");
    }

    @Test
    void plainMemberWhoIsNotStaff_is403() {
        when(staffRepo.existsByGroupIdAndUserEmailIgnoreCase("g-1", "resident@example.com")).thenReturn(false);
        assertThatThrownBy(() -> auth().requireAgencyStaffOrAdmin(agency(true), "resident@example.com"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void strangerIsNot403SilentlyPassed() {
        when(staffRepo.existsByGroupIdAndUserEmailIgnoreCase("g-1", "nobody@example.com")).thenReturn(false);
        assertThatThrownBy(() -> auth().requireAgencyStaffOrAdmin(agency(true), "nobody@example.com"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void staffOnAGroupThatIsNotAgencyAuthorized_is403() {
        // A staff row must not grant anything on a group that was never stamped.
        when(staffRepo.existsByGroupIdAndUserEmailIgnoreCase("g-1", "crew@city.gov")).thenReturn(true);
        assertThatThrownBy(() -> auth().requireAgencyStaffOrAdmin(agency(false), "crew@city.gov"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Not an authorized agency");
    }

    @Test
    void missingGroup_is404() {
        assertThatThrownBy(() -> auth().requireAgencyStaffOrAdmin(null, "admin@city.gov"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void nullCaller_is403_andNeverQueriesStaff() {
        assertThatThrownBy(() -> auth().requireAgencyStaffOrAdmin(agency(true), null))
                .isInstanceOf(ResponseStatusException.class);
        verify(staffRepo, never()).existsByGroupIdAndUserEmailIgnoreCase(anyString(), anyString());
    }

    @Test
    void requireAgencyAdmin_stillRejectsStaff_soWritesStayAdminOnly() {
        // Guards the read/write split: widening reads must not widen mutations.
        when(staffRepo.existsByGroupIdAndUserEmailIgnoreCase("g-1", "crew@city.gov")).thenReturn(true);
        assertThatThrownBy(() -> auth().requireAgencyAdmin(agency(true), "crew@city.gov"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Group admin or owner role required");
    }

    // ── the email guard on the grant ────────────────────────────────────────

    @Test
    void add_rejectsMalformedEmail_beforeCreatingAGrant() {
        for (String bad : List.of("notanemail", "no-at-sign.com", "missing@tld", "two @spaces.com", "@nolocal.com")) {
            assertThatThrownBy(() -> staffService().add("g-1", bad, "admin@city.gov"))
                    .as("should reject %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(staffRepo, never()).save(any());
    }

    @Test
    void add_rejectsBlank() {
        assertThatThrownBy(() -> staffService().add("g-1", "   ", "admin@city.gov"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> staffService().add("g-1", null, "admin@city.gov"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(staffRepo, never()).save(any());
    }

    @Test
    void add_acceptsAValidAddress_andNormalizesIt() {
        when(staffRepo.findByGroupIdAndUserEmailIgnoreCase("g-1", "crew@city.gov")).thenReturn(Optional.empty());
        when(staffRepo.save(any(AgencyStaff.class))).thenAnswer(inv -> inv.getArgument(0));

        AgencyStaff saved = staffService().add("g-1", "  Crew@City.Gov  ", "Admin@City.Gov");

        assertThat(saved.getUserEmail()).isEqualTo("crew@city.gov");
        assertThat(saved.getAddedBy()).isEqualTo("admin@city.gov");
        assertThat(saved.getGroupId()).isEqualTo("g-1");
    }

    @Test
    void add_isIdempotent_returningTheExistingRowWithoutASecondInsert() {
        AgencyStaff existing = new AgencyStaff();
        existing.setGroupId("g-1");
        existing.setUserEmail("crew@city.gov");
        when(staffRepo.findByGroupIdAndUserEmailIgnoreCase("g-1", "crew@city.gov"))
                .thenReturn(Optional.of(existing));

        assertThat(staffService().add("g-1", "crew@city.gov", "admin@city.gov")).isSameAs(existing);
        verify(staffRepo, never()).save(any());
    }
}
