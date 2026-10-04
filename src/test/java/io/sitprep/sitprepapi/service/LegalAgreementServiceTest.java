package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.LegalAgreement;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.LegalAgreementDtos.RecordLegalAgreementRequest;
import io.sitprep.sitprepapi.repo.LegalAgreementRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegalAgreementServiceTest {
    private final LegalAgreementRepo agreements = mock(LegalAgreementRepo.class);
    private final UserInfoRepo users = mock(UserInfoRepo.class);
    private final LegalAgreementService service = new LegalAgreementService(agreements, users);

    @Test
    void recordsOneRowPerDocumentForAccountConsent() {
        UserInfo user = new UserInfo();
        user.setId("user-123");
        user.setFirebaseUid("firebase-123");
        user.setUserEmail("person@example.com");

        when(users.findByFirebaseUid("firebase-123")).thenReturn(Optional.of(user));
        when(agreements.save(any(LegalAgreement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var saved = service.record(
                new RecordLegalAgreementRequest(
                        List.of("TERMS", "privacy", "emergency-disclaimer", "COMMUNITY_GUIDELINES"),
                        "legal-2026-10-04-draft-attorney-review",
                        "2026-10-04",
                        "account",
                        "email-signup-scroll-to-agree"
                ),
                "firebase-123",
                "person@example.com"
        );

        assertThat(saved).hasSize(4);
        assertThat(saved).extracting("userId").containsOnly("user-123");
        assertThat(saved).extracting("authenticationState").containsOnly("ACCOUNT");
        assertThat(saved).extracting("documentType")
                .containsExactly("TERMS", "PRIVACY", "EMERGENCY_DISCLAIMER", "COMMUNITY_GUIDELINES");
    }
}
