package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdEvent;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdEventRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A status write must never roll back because the household event log could
 * not be written. The failure here is a REAL one inside the database — a
 * household id longer than {@code household_event.household_id} (64) — so the
 * repository's own transaction interceptor sees it, exactly as a prod failure
 * would. Before the fix the recorder's catch swallowed the exception but the
 * caller's transaction was already rollback-only, and the user's SAFE failed
 * with UnexpectedRollbackException.
 */
@SpringBootTest
@ActiveProfiles("test")
class HouseholdEventIsolationTest {

    @MockBean WebSocketMessageSender ws;
    @MockBean NotificationService notifications;

    @Autowired UserInfoService userInfoService;
    @Autowired GroupRepo groups;
    @Autowired UserInfoRepo users;
    @Autowired HouseholdEventRepo eventRepo;

    @Test
    void aFailingEventSaveLeavesTheStatusWriteCommitted() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        String email = "iso-" + sfx + "@x.com";
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setUserFirstName("Iso");
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        users.save(u);
        // 70 chars: fits groups.group_id, overflows household_event.household_id.
        String longId = "hh-" + "x".repeat(67 - sfx.length()) + sfx;
        assertThat(longId).hasSize(70);
        group(longId, email);

        assertThatCode(() -> userInfoService.updateSelfStatusByEmail(email, "SAFE", null, null))
                .doesNotThrowAnyException();

        UserInfo after = users.findByUserEmailIgnoreCase(email).orElseThrow();
        assertThat(after.getUserStatus()).isEqualTo("SAFE");
        List<HouseholdEvent> rows = eventRepo.findRange(longId, Instant.EPOCH, Instant.now().plusSeconds(5));
        assertThat(rows).isEmpty();
    }

    @Test
    void aWorkingEventSaveIsStillRecordedOnce() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        String email = "ok-" + sfx + "@x.com";
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setUserFirstName("Ok");
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        users.save(u);
        String hid = "g-" + UUID.randomUUID();
        group(hid, email);

        userInfoService.updateSelfStatusByEmail(email, "SAFE", null, null);

        List<HouseholdEvent> rows = eventRepo.findRangeByKind(hid,
                HouseholdEventService.KIND_STATUS_CHANGED, Instant.EPOCH, Instant.now().plusSeconds(5));
        assertThat(rows).hasSize(1);
    }

    private void group(String id, String owner) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupType("Household");
        g.setGroupName("Iso household");
        g.setPrivacy("Private");
        g.setAlert("Not Active");
        g.setOwnerEmail(owner);
        g.setAdminEmails(new ArrayList<>(List.of(owner)));
        g.setMemberEmails(new ArrayList<>(List.of(owner)));
        g.setPendingMemberEmails(new ArrayList<>());
        g.setCreatedAt(Instant.now());
        g.setUpdatedAt(Instant.now());
        groups.save(g);
    }
}
