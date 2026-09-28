package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.resource.UserInfoResource;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** docs/epics/privacy-push-token-and-phone/EXEC.md — both reproduced on prod 2026-09-27. */
class PrivacyPushTokenAndPhoneTest {

    // ── 1 · sign-out releases THIS device's push token ─────────────────────

    @Nested
    class PushTokenRelease {
        private UserInfoRepo repo;
        private UserInfoService service;
        private UserInfo me;

        @BeforeEach
        void setUp() {
            repo = mock(UserInfoRepo.class);
            service = new UserInfoService(repo, mock(HouseholdEventService.class), mock(GroupRepo.class),
                    mock(PostService.class), mock(FollowService.class), mock(BlockService.class),
                    new ObjectMapper(), mock(WebSocketMessageSender.class),
                    mock(LocationPresenceService.class), mock(HouseholdProvisioningService.class));
            me = new UserInfo();
            me.setUserEmail("a@x.com");
            me.setFcmtoken("token-phone-A");
            when(repo.findByUserEmailIgnoreCase("a@x.com")).thenReturn(Optional.of(me));
            when(repo.save(any(UserInfo.class))).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        void thisDevicesTokenIsCleared() {
            assertThat(service.releaseFcmTokenByEmail("a@x.com", "token-phone-A")).isTrue();
            assertThat(me.getFcmtoken()).isNull();
        }

        @Test
        void aNewerPhonesTokenSurvivesAnOlderPhoneSigningOut() {
            assertThat(service.releaseFcmTokenByEmail("a@x.com", "token-phone-OLD")).isFalse();
            assertThat(me.getFcmtoken()).isEqualTo("token-phone-A");
            verify(repo, never()).save(any());
        }

        @Test
        void aDeviceThatCannotReadItsTokenStillReleases() {
            assertThat(service.releaseFcmTokenByEmail("a@x.com", null)).isTrue();
            assertThat(me.getFcmtoken()).isNull();
        }

        @Test
        void nothingStoredIsANoOp() {
            me.setFcmtoken(null);
            assertThat(service.releaseFcmTokenByEmail("a@x.com", "token-phone-A")).isFalse();
            verify(repo, never()).save(any());
        }
    }

    // ── 2 · a group owner's phone is for members ───────────────────────────

    @Nested
    class OwnerContact {
        private GroupRepo groups;
        private UserInfoRepo users;
        private GroupOwnerContactService service;
        private Group sub;
        private Group parent;

        private Group group(String id, String owner, String... members) {
            Group g = new Group();
            g.setGroupId(id);
            g.setOwnerEmail(owner);
            g.setAdminEmails(new ArrayList<>());
            g.setMemberEmails(new ArrayList<>(List.of(members)));
            g.setSubGroupIDs(new ArrayList<>());
            g.setParentGroupIDs(new ArrayList<>());
            when(groups.findByGroupId(id)).thenReturn(Optional.of(g));
            return g;
        }

        @BeforeEach
        void setUp() {
            groups = mock(GroupRepo.class);
            users = mock(UserInfoRepo.class);
            service = new GroupOwnerContactService(groups, users);
            sub = group("class-3b", "teacher@x.com", "kid-parent@x.com");
            parent = group("school", "principal@x.com", "school-parent@x.com");
            UserInfo teacher = new UserInfo();
            teacher.setUserEmail("teacher@x.com");
            teacher.setPhone(" 555-0199 ");
            when(users.findByUserEmailIgnoreCase("teacher@x.com")).thenReturn(Optional.of(teacher));
        }

        private void linkBothSides() {
            parent.getSubGroupIDs().add("class-3b");
            sub.getParentGroupIDs().add("school");
        }

        @Test
        void aMemberOfTheGroupGetsTheOwnersPhone() {
            assertThat(service.ownerContact("class-3b", "KID-PARENT@x.com"))
                    .contains(new GroupOwnerContactService.OwnerContact("teacher@x.com", "555-0199"));
        }

        @Test
        void aMemberOfATwoSidedParentGetsIt() {
            linkBothSides();
            assertThat(service.ownerContact("class-3b", "school-parent@x.com")).isPresent();
        }

        @Test
        void aParentThatMerelyListsTheGroupDoesNotGetIt() {
            // Anyone can write a stranger's group id into their own subGroupIDs.
            parent.getSubGroupIDs().add("class-3b");
            assertThat(service.ownerContact("class-3b", "school-parent@x.com")).isEmpty();
        }

        @Test
        void aGroupThatMerelyClaimsAParentDoesNotHandItsParentsMembersIn() {
            sub.getParentGroupIDs().add("school");
            assertThat(service.ownerContact("class-3b", "school-parent@x.com")).isEmpty();
        }

        @Test
        void aStrangerAndAnUnknownGroupGetTheSameNothing() {
            assertThat(service.ownerContact("class-3b", "stranger@x.com")).isEmpty();
            assertThat(service.ownerContact("no-such-group", "kid-parent@x.com")).isEmpty();
        }

        @Test
        void noNumberOnFileIsNullNotAPlaceholder() {
            UserInfo t = new UserInfo();
            t.setUserEmail("teacher@x.com");
            t.setPhone("   ");
            when(users.findByUserEmailIgnoreCase("teacher@x.com")).thenReturn(Optional.of(t));
            assertThat(service.ownerContact("class-3b", "kid-parent@x.com"))
                    .contains(new GroupOwnerContactService.OwnerContact("teacher@x.com", null));
        }
    }

    // ── 2b · phone is self-only on the cross-user lookups ──────────────────

    @Test
    @SuppressWarnings("unchecked")
    void phoneIsOnTheSelfOnlyList() throws Exception {
        Field f = UserInfoResource.class.getDeclaredField("SELF_ONLY_FIELDS");
        f.setAccessible(true);
        assertThat((Set<String>) f.get(null)).contains("phone", "fcmtoken", "address");
    }
}
