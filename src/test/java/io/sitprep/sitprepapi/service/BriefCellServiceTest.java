package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EXEC-3B: cells are 0.1° grid points built from coordinates only. The anchor
 * is the grid point, so even a one-member cell never carries a real address.
 */
class BriefCellServiceTest {

    @Test
    void cellsAreGridPointsDedupedAcrossSources() {
        UserSavedLocationRepo saved = mock(UserSavedLocationRepo.class);
        GroupRepo groups = mock(GroupRepo.class);
        UserInfoRepo users = mock(UserInfoRepo.class);
        when(saved.findHomeCoordinates()).thenReturn(List.<Object[]>of(new Object[]{40.4317, -111.8888}));
        when(groups.findHouseholdCoordinates()).thenReturn(List.<Object[]>of(new Object[]{40.41, -111.92}));
        when(users.findHomeCoordinates()).thenReturn(List.<Object[]>of(new Object[]{37.7749, -122.4194}));
        when(users.findFreshLocationCoordinates(any())).thenReturn(List.<Object[]>of(
                new Object[]{0.0, 0.0},          // null island: a missing fix, not a place
                new Object[]{95.0, 10.0},        // impossible latitude
                new Object[]{null, 10.0}));

        BriefCellService svc = new BriefCellService(saved, groups, users,
                Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC));
        List<BriefCellService.Cell> cells = svc.cells();

        assertThat(cells).extracting(BriefCellService.Cell::key)
                .containsExactly("37.8|-122.4", "40.4|-111.9");
        BriefCellService.Cell lehi = cells.get(1);
        // The anchor is the grid point, never the raw home coordinate.
        assertThat(lehi.lat()).isEqualTo(40.4);
        assertThat(lehi.lng()).isEqualTo(-111.9);
    }

    @Test
    void keysAreLocaleSafe() {
        assertThat(BriefCellService.keyFor(40.4317, -111.8888)).isEqualTo("40.4|-111.9");
    }
}
