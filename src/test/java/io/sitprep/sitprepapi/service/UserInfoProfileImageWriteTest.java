package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-1 (2026-09-27): every UserInfoService path that writes
 * {@code profileImageUrl} goes through the host allow-list.
 *
 * <ul>
 *   <li>PUT /api/userinfo/{id} ({@code updateUserById}) — 400 on a changed, disallowed value;</li>
 *   <li>PATCH /api/userinfo/{id} ({@code patchUserById}) — 400, and NOT swallowed by the
 *       reflective try/catch;</li>
 *   <li>sign-in upserts ({@code applyPatch}) — dropped, never stored, never a 400.</li>
 * </ul>
 */
class UserInfoProfileImageWriteTest {

    private static final String LEGACY = "https://pub-0123.r2.dev/profile/old.jpg";
    private static final String GOOD = "https://sitprepimages.com/profile/new.jpg";
    private static final String BAD = "https://attacker.example/px.gif";

    private UserInfoRepo repo;
    private UserInfoService service;
    private UserInfo stored;

    @BeforeEach
    void setUp() {
        repo = mock(UserInfoRepo.class);
        service = new UserInfoService(repo, mock(HouseholdEventService.class), mock(GroupRepo.class),
                mock(PostService.class), mock(FollowService.class), mock(BlockService.class),
                new ObjectMapper(), mock(WebSocketMessageSender.class),
                mock(LocationPresenceService.class), mock(HouseholdProvisioningService.class));
        stored = new UserInfo();
        stored.setId("u-1");
        stored.setUserEmail("a@x.com");
        stored.setUserStatus("SAFE");
        stored.setProfileImageUrl(LEGACY);
        when(repo.findById("u-1")).thenReturn(Optional.of(stored));
        when(repo.findByUserEmailIgnoreCase("a@x.com")).thenReturn(Optional.of(stored));
        when(repo.save(any(UserInfo.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static UserInfo body(String avatar) {
        UserInfo u = new UserInfo();
        u.setUserFirstName("Ann");
        u.setUserStatus("SAFE");
        u.setProfileImageUrl(avatar);
        return u;
    }

    // ── PUT ────────────────────────────────────────────────────────────────

    @Test
    void putRejectsADisallowedHost() {
        assertThatThrownBy(() -> service.updateUserById("u-1", body(BAD)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).save(any());
        assertThat(stored.getProfileImageUrl()).isEqualTo(LEGACY);
    }

    @Test
    void putEchoingAnUnchangedLegacyAvatarIsNotRejudged() {
        UserInfo saved = service.updateUserById("u-1", body(LEGACY));
        assertThat(saved.getProfileImageUrl()).isEqualTo(LEGACY);
        assertThat(saved.getUserFirstName()).isEqualTo("Ann");
    }

    @Test
    void putStoresAnAllowedHostAndBlankClears() {
        assertThat(service.updateUserById("u-1", body(GOOD)).getProfileImageUrl()).isEqualTo(GOOD);
        assertThat(service.updateUserById("u-1", body("  ")).getProfileImageUrl()).isNull();
        assertThat(service.updateUserById("u-1", body(null)).getProfileImageUrl()).isNull();
    }

    // ── PATCH ──────────────────────────────────────────────────────────────

    @Test
    void patchRejectsADisallowedHostInsteadOfSwallowingIt() {
        Map<String, Object> updates = new HashMap<>();
        updates.put("profileImageUrl", BAD);
        assertThatThrownBy(() -> service.patchUserById("u-1", updates))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stored.getProfileImageUrl()).isEqualTo(LEGACY);
    }

    @Test
    void patchRejectsANonString() {
        Map<String, Object> updates = new HashMap<>();
        updates.put("profileImageUrl", Map.of("url", GOOD));
        assertThatThrownBy(() -> service.patchUserById("u-1", updates))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void patchStoresAllowedClearsBlankAndKeepsUnchangedLegacy() {
        assertThat(service.patchUserById("u-1", Map.of("profileImageUrl", LEGACY))
                .getProfileImageUrl()).isEqualTo(LEGACY);
        assertThat(service.patchUserById("u-1", Map.of("profileImageUrl", GOOD))
                .getProfileImageUrl()).isEqualTo(GOOD);
        assertThat(service.patchUserById("u-1", Map.of("profileImageUrl", ""))
                .getProfileImageUrl()).isNull();
    }

    // ── sign-in upsert ─────────────────────────────────────────────────────

    @Test
    void upsertDropsADisallowedHostWithoutFailingSignIn() {
        UserInfo saved = service.upsertByEmail("a@x.com", body(BAD));
        assertThat(saved.getProfileImageUrl()).isEqualTo(LEGACY);
        assertThat(saved.getUserFirstName()).isEqualTo("Ann"); // the rest of the upsert still applied
    }

    @Test
    void upsertStoresAProviderPhoto() {
        String google = "https://lh3.googleusercontent.com/a/ACg8oc=s96-c";
        assertThat(service.upsertByEmail("a@x.com", body(google)).getProfileImageUrl()).isEqualTo(google);
    }

    // ── nudge transaction ──────────────────────────────────────────────────

    @Test
    void nudgeMemberIsTheTransactionalElement() throws Exception {
        assertThat(GroupService.class
                .getMethod("nudgeMember", String.class, String.class, String.class)
                .isAnnotationPresent(Transactional.class))
                .as("@Transactional must be on nudgeMember, where Spring's proxy sees it")
                .isTrue();
        assertThat(GroupService.NudgeResult.class.isAnnotationPresent(Transactional.class)).isFalse();
    }
}
