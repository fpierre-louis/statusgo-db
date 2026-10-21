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
                List.of(), List.of(), List.of(), null, null);
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
}
