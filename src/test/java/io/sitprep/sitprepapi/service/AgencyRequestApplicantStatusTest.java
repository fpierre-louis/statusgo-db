package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.VerificationApplication;
import io.sitprep.sitprepapi.domain.VerificationApplicationNote;
import io.sitprep.sitprepapi.dto.AgencyApplicantResponseRequest;
import io.sitprep.sitprepapi.dto.AgencyRequestSubmissionDto;
import io.sitprep.sitprepapi.dto.CreateAgencyRequestRequest;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.VerificationApplicationNoteRepo;
import io.sitprep.sitprepapi.repo.VerificationApplicationRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgencyRequestApplicantStatusTest {

    @Mock VerificationApplicationRepo applicationRepo;
    @Mock VerificationApplicationNoteRepo noteRepo;
    @Mock GroupRepo groupRepo;
    @Mock VerificationApplicationService verificationService;
    @Mock AgencyAuthorizationService authorizationService;
    @Mock AdminAuditLogService audit;

    AgencyRequestService service;

    @BeforeEach
    void setUp() {
        service = new AgencyRequestService(
                applicationRepo, noteRepo, groupRepo, verificationService, authorizationService, audit);
        when(applicationRepo.save(any(VerificationApplication.class))).thenAnswer(invocation -> {
            VerificationApplication app = invocation.getArgument(0);
            if (app.getId() == null) app.setId(71L);
            return app;
        });
    }

    @Test
    void createReturnsOneTimeTokenButPersistsOnlyItsHash() {
        AgencyRequestSubmissionDto submission = service.create(new CreateAgencyRequestRequest(
                "contact@city.gov", "Example City", "Alex", "Coordinator",
                "Resident alerts", "City limits"), null);

        assertThat(submission.id()).isEqualTo(71L);
        assertThat(submission.statusToken()).hasSizeGreaterThan(32);
        ArgumentCaptor<VerificationApplication> saved = ArgumentCaptor.forClass(VerificationApplication.class);
        verify(applicationRepo).save(saved.capture());
        assertThat(saved.getValue().getStatusTokenHash()).hasSize(64);
        assertThat(saved.getValue().getStatusTokenHash()).doesNotContain(submission.statusToken());
        assertThat(saved.getValue().getStatusTokenExpiresAt()).isAfter(saved.getValue().getSubmittedAt());
    }

    @Test
    void statusIsHiddenWithoutOwnershipOrValidCapabilityToken() {
        AgencyRequestSubmissionDto submission = service.create(new CreateAgencyRequestRequest(
                "contact@city.gov", "Example City", null, null, null, "City limits"), null);
        VerificationApplication app = capturedApplication();
        when(applicationRepo.findById(71L)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.applicantStatus(71L, "wrong", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(service.applicantStatus(71L, submission.statusToken(), null).status())
                .isEqualTo("SUBMITTED");
        assertThat(service.applicantStatus(71L, null, "contact@city.gov").id()).isEqualTo(71L);
    }

    @Test
    void needsInfoResponseBecomesReviewerVisibleAndReturnsToReview() {
        AgencyRequestSubmissionDto submission = service.create(new CreateAgencyRequestRequest(
                "contact@city.gov", "Example City", null, null, null, "City limits"), null);
        VerificationApplication app = capturedApplication();
        app.setStatus(VerificationApplication.Status.NEEDS_INFO);
        when(applicationRepo.findById(71L)).thenReturn(Optional.of(app));

        var status = service.applicantRespond(71L,
                new AgencyApplicantResponseRequest("Our service map is attached in the shared folder."),
                submission.statusToken(), null);

        assertThat(status.status()).isEqualTo("IN_REVIEW");
        assertThat(status.applicantStatusNote()).contains("received");
        ArgumentCaptor<VerificationApplicationNote> note = ArgumentCaptor.forClass(VerificationApplicationNote.class);
        verify(noteRepo).save(note.capture());
        assertThat(note.getValue().getAuthorEmail()).isEqualTo("contact@city.gov");
        assertThat(note.getValue().getNote()).contains("service map");
    }

    private VerificationApplication capturedApplication() {
        ArgumentCaptor<VerificationApplication> captor = ArgumentCaptor.forClass(VerificationApplication.class);
        verify(applicationRepo).save(captor.capture());
        return captor.getValue();
    }
}
