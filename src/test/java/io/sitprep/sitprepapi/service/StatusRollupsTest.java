package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.dto.GroupMemberViewDto.StatusRollup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dominantStatus derivation — severity-first so the label never hides a
 * member in trouble. (The rollup math itself is covered by the Phase 1
 * member-view parity scenarios; this pins the new derivation only.)
 */
class StatusRollupsTest {

    private static StatusRollup r(int total, int safe, int help, int injured, int noResponse) {
        return new StatusRollup(total, safe + help + injured, safe, help, injured, noResponse);
    }

    @Test
    void emptyRoster_isUnknown() {
        assertThat(StatusRollups.dominantStatus(r(0, 0, 0, 0, 0))).isEqualTo("UNKNOWN");
        assertThat(StatusRollups.dominantStatus(null)).isEqualTo("UNKNOWN");
    }

    @Test
    void injuredOutranksEverything() {
        assertThat(StatusRollups.dominantStatus(r(4, 2, 1, 1, 0))).isEqualTo("INJURED");
    }

    @Test
    void helpOutranksSilenceAndSafe() {
        assertThat(StatusRollups.dominantStatus(r(4, 2, 1, 0, 1))).isEqualTo("HELP");
    }

    @Test
    void allSilent_isUnknown() {
        assertThat(StatusRollups.dominantStatus(r(3, 0, 0, 0, 3))).isEqualTo("UNKNOWN");
    }

    @Test
    void partiallyAccounted_isCheckIn() {
        assertThat(StatusRollups.dominantStatus(r(3, 2, 0, 0, 1))).isEqualTo("CHECK_IN");
    }

    @Test
    void everyoneSafe_isSafe() {
        assertThat(StatusRollups.dominantStatus(r(3, 3, 0, 0, 0))).isEqualTo("SAFE");
    }
}
