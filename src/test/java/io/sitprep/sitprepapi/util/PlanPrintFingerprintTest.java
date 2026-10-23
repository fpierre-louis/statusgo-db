package io.sitprep.sitprepapi.util;

import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.dto.HouseholdPlanDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Printables P8: only content that prints moves the fingerprint. */
class PlanPrintFingerprintTest {

    private static MeetingPlace spot(long id, String name, String address) {
        MeetingPlace m = new MeetingPlace();
        m.setId(id);
        m.setName(name);
        m.setAddress(address);
        m.setTierKey("outside_home");
        return m;
    }

    private static HouseholdPlanDto doc(List<MeetingPlace> spots, Instant confirmed, String name) {
        return new HouseholdPlanDto("hh1", name, "4053 Rivermist Ln N", "40.39", "-111.85", "84043",
                null, spots, List.of(), List.of(), null, List.of(), List.of(), confirmed,
                List.of(), List.of(), List.of(), null, null, List.of());
    }

    @Test
    void stableAcrossIdsTimestampsOrderAndConfirmation() {
        String a = PlanPrintFingerprint.of(doc(List.of(spot(1, "Mailbox", "curb"), spot(2, "Library", "120 N Center")), null, "Pierre-Louis"));
        String b = PlanPrintFingerprint.of(doc(List.of(spot(9, "Library", "120 N Center"), spot(8, "Mailbox", "curb")), Instant.now(), "Pierre-Louis"));
        assertThat(a).startsWith("sha256:").isEqualTo(b);
    }

    @Test
    void anEditAnAdditionAndADeletionEachChangeIt() {
        String base = PlanPrintFingerprint.of(doc(List.of(spot(1, "Mailbox", "curb"), spot(2, "Library", "120 N Center")), null, "Pierre-Louis"));
        String edited = PlanPrintFingerprint.of(doc(List.of(spot(1, "Mailbox", "curb"), spot(2, "Library", "121 N Center")), null, "Pierre-Louis"));
        String added = PlanPrintFingerprint.of(doc(List.of(spot(1, "Mailbox", "curb"), spot(2, "Library", "120 N Center"), spot(3, "UVU", "Orem")), null, "Pierre-Louis"));
        String deleted = PlanPrintFingerprint.of(doc(List.of(spot(1, "Mailbox", "curb")), null, "Pierre-Louis"));
        String renamed = PlanPrintFingerprint.of(doc(List.of(spot(1, "Mailbox", "curb"), spot(2, "Library", "120 N Center")), null, "Pierre-Louis family"));
        assertThat(List.of(edited, added, deleted, renamed)).doesNotContain(base);
    }

    @Test
    void timestampKeysAreIgnoredButLatLngAreNot() {
        assertThat(PlanPrintFingerprint.ignoredKey("updatedAt")).isTrue();
        assertThat(PlanPrintFingerprint.ignoredKey("expiresAt")).isTrue();
        assertThat(PlanPrintFingerprint.ignoredKey("id")).isTrue();
        assertThat(PlanPrintFingerprint.ignoredKey("lat")).isFalse();
        assertThat(PlanPrintFingerprint.ignoredKey("format")).isFalse();
        assertThat(PlanPrintFingerprint.ignoredKey("name")).isFalse();
    }

    // -- EXEC-R1: household routines print ------------------------------------

    private static io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.HouseholdRoutineDto routine(String key, String ifText, String thenText, Instant at) {
        return new io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.HouseholdRoutineDto(key, "tornado", "Title", ifText, thenText,
                List.of(new io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.SlotValueDto("who", "Sam", "user:u-3")), 1, at);
    }

    private static HouseholdPlanDto withRoutines(List<io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.HouseholdRoutineDto> routines) {
        HouseholdPlanDto d = doc(List.of(spot(1, "Mailbox", "curb")), null, "Pierre-Louis");
        return new HouseholdPlanDto(d.householdId(), d.name(), d.address(), d.latitude(), d.longitude(), d.zipCode(),
                d.demographic(), d.meetingPlaces(), d.evacuationPlans(), d.originLocations(), d.mealPlan(), d.contactGroups(),
                d.goBags(), d.planLastConfirmedAt(), d.supportProfiles(), d.supportAssignments(), d.standingConditions(),
                null, null, routines);
    }

    @Test
    void aHouseholdWithoutRoutinesKeepsTheFingerprintItsPaperWasStampedWith() {
        String base = PlanPrintFingerprint.of(doc(List.of(spot(1, "Mailbox", "curb")), null, "Pierre-Louis"));
        assertThat(PlanPrintFingerprint.of(withRoutines(null))).isEqualTo(base);
        assertThat(PlanPrintFingerprint.of(withRoutines(List.of()))).isEqualTo(base);
        // The literal pre-R1 value: adding the routines section must not move it.
        assertThat(base).isEqualTo(PRE_R1_BASE);
    }

    static final String PRE_R1_BASE = "sha256:469011911d26356fc44b3d9855f1671ede232ee1bc9d41e4e8dc9c8303756ae3";

    @Test
    void savingChangingAndRemovingARoutineChangesTheFingerprint() {
        Instant t1 = Instant.parse("2026-10-23T12:00:00Z");
        String none = PlanPrintFingerprint.of(withRoutines(List.of()));
        var saved = routine("tornado-night-routine", "If a tornado warning comes at night", "Sam wakes everyone.", t1);
        String one = PlanPrintFingerprint.of(withRoutines(List.of(saved)));
        var changed = routine("tornado-night-routine", "If a tornado warning comes at night", "Adam wakes everyone.", t1);
        String edited = PlanPrintFingerprint.of(withRoutines(List.of(changed)));
        assertThat(List.of(one, edited)).doesNotContain(none);
        assertThat(one).isNotEqualTo(edited);
        // Removing it returns to the unprinted-routine fingerprint.
        assertThat(PlanPrintFingerprint.of(withRoutines(List.of()))).isEqualTo(none);
    }

    @Test
    void routineOrderTimestampsAndSlotRefsDoNotMoveIt() {
        Instant t1 = Instant.parse("2026-10-23T12:00:00Z");
        Instant t2 = Instant.parse("2026-11-01T09:30:00Z");
        var a = routine("tornado-night-routine", "If A", "Then A", t1);
        var b = routine("fire-exit-routine", "If B", "Then B", t1);
        var aLater = routine("tornado-night-routine", "If A", "Then A", t2);
        assertThat(PlanPrintFingerprint.of(withRoutines(List.of(a, b))))
                .isEqualTo(PlanPrintFingerprint.of(withRoutines(List.of(b, aLater))));
    }
}
