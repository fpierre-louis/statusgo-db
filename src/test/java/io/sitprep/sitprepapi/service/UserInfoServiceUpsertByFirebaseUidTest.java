package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for Firebase-backed account provisioning. The frontend may
 * retry or accidentally double-deliver signup writes; the backend guarantee is
 * that UID identity, not request count, owns the user row.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserInfoServiceUpsertByFirebaseUidTest {

    @Autowired UserInfoService userInfoService;
    @Autowired UserInfoRepo userInfoRepo;

    @Test
    void upsertByFirebaseUid_isRetrySafeAndDoesNotClobberExistingPatchFields() {
        String uid = "uid-idempotent-" + UUID.randomUUID();
        String email = "idempotent-" + UUID.randomUUID() + "@example.com";

        UserInfo first = new UserInfo();
        first.setUserEmail(email);
        first.setUserFirstName("Ada");
        first.setUserLastName("Lovelace");
        first.setTitle("Prepared Neighbor");
        first.setProfileImageUrl("https://cdn.example.com/ada.png");

        UserInfo firstSaved = userInfoService.upsertByFirebaseUid(uid, first);

        UserInfo retry = new UserInfo();
        retry.setUserEmail(email);

        UserInfo secondSaved = userInfoService.upsertByFirebaseUid(uid, retry);

        assertThat(secondSaved.getId())
                .as("same Firebase UID must resolve to the same row")
                .isEqualTo(firstSaved.getId());
        assertThat(userInfoRepo.findAll().stream()
                        .filter(u -> uid.equals(u.getFirebaseUid()))
                        .count())
                .as("duplicate delivery must not create a second user_info row")
                .isEqualTo(1);

        UserInfo persisted = userInfoRepo.findByFirebaseUid(uid).orElseThrow();
        assertThat(persisted.getUserFirstName()).isEqualTo("Ada");
        assertThat(persisted.getUserLastName()).isEqualTo("Lovelace");
        assertThat(persisted.getTitle()).isEqualTo("Prepared Neighbor");
        assertThat(persisted.getProfileImageUrl()).isEqualTo("https://cdn.example.com/ada.png");
    }
}
