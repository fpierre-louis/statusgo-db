package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.dto.UserSavedLocationWriteDto;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The single-home invariant, and the partial-update contract around it.
 *
 * <p>Until 2026-08-31 <b>no row could ever be home.</b> The write endpoints
 * bound the entity, whose Jackson property is {@code home} (Lombok's
 * {@code isHome()} getter) while the read DTO pins {@code isHome} — so both
 * frontend writers were dropped, every {@code setHome(true)} was gated on a
 * flag that was always false, and {@code homeFor()} always returned empty.
 * These pin the behaviour now that the flag actually arrives.</p>
 *
 * <p>Geocode is only reached when coordinates change, so a null service is safe
 * for the rename cases and the coord-bearing cases pass a stub through.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserSavedLocationHomeFlagTest {

    @Mock UserSavedLocationRepo repo;

    private UserSavedLocationService service() {
        return new UserSavedLocationService(repo, null);
    }

    private static UserSavedLocation row(long id, String owner, boolean home) {
        UserSavedLocation e = new UserSavedLocation();
        e.setId(id);
        e.setOwnerEmail(owner);
        e.setName("Place " + id);
        e.setLatitude(40.34);
        e.setLongitude(-111.79);
        e.setHome(home);
        return e;
    }

    @Test
    @DisplayName("create with isHome=true produces a home row")
    void createHome() {
        when(repo.findFirstByOwnerEmailIgnoreCaseAndIsHomeTrue("a@b.c")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        UserSavedLocation saved = service().create("A@B.c",
                new UserSavedLocationWriteDto("Home", null, 40.34, -111.79, true));

        assertThat(saved.isHome()).isTrue();
        assertThat(saved.getOwnerEmail()).isEqualTo("a@b.c"); // normalised
    }

    @Test
    @DisplayName("create with isHome absent produces a non-home row")
    void createWithoutFlag() {
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        UserSavedLocation saved = service().create("a@b.c",
                new UserSavedLocationWriteDto("Work", null, 40.34, -111.79, null));

        assertThat(saved.isHome()).isFalse();
    }

    @Test
    @DisplayName("a rename does NOT demote the user's home")
    void renameLeavesHomeAlone() {
        // The regression the fix could have introduced, and the one the frontend
        // had a workaround for: update is partial, so an omitted flag must mean
        // unchanged. A primitive would arrive as `false` and un-home the place
        // as a side effect of renaming it.
        UserSavedLocation existing = row(7L, "a@b.c", true);
        when(repo.findById(7L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        UserSavedLocation saved = service().update(7L,
                new UserSavedLocationWriteDto("Renamed", null, null, null, null));

        assertThat(saved.getName()).isEqualTo("Renamed");
        assertThat(saved.isHome()).as("still home after a rename").isTrue();
    }

    @Test
    @DisplayName("an explicit isHome=false IS a demotion")
    void explicitFalseDemotes() {
        UserSavedLocation existing = row(7L, "a@b.c", true);
        when(repo.findById(7L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        UserSavedLocation saved = service().update(7L,
                new UserSavedLocationWriteDto(null, null, null, null, false));

        assertThat(saved.isHome()).isFalse();
    }

    @Test
    @DisplayName("promoting a row demotes the previous home — one home per user")
    void promotionDemotesPrior() {
        UserSavedLocation prior = row(1L, "a@b.c", true);
        UserSavedLocation target = row(2L, "a@b.c", false);
        when(repo.findById(2L)).thenReturn(Optional.of(target));
        when(repo.findFirstByOwnerEmailIgnoreCaseAndIsHomeTrue("a@b.c")).thenReturn(Optional.of(prior));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        UserSavedLocation saved = service().update(2L,
                new UserSavedLocationWriteDto(null, null, null, null, true));

        assertThat(saved.isHome()).isTrue();
        assertThat(prior.isHome()).as("previous home demoted").isFalse();
    }

    @Test
    @DisplayName("promoting the row that is ALREADY home touches nothing else")
    void alreadyHomeIsANoop() {
        UserSavedLocation existing = row(7L, "a@b.c", true);
        when(repo.findById(7L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        service().update(7L, new UserSavedLocationWriteDto(null, null, null, null, true));

        // No lookup for a prior home: the promote branch is guarded on
        // `!existing.isHome()`, so re-asserting home must not go hunting for a
        // row to demote — which, being the same row, it would then demote.
        verify(repo, never()).findFirstByOwnerEmailIgnoreCaseAndIsHomeTrue(any());
    }
}
