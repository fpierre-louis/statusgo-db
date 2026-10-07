package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.CreateActivationRequest;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.RecipientsRequest;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An activation keeps only the contact groups its owner can already read
 * (EXEC-A1, found while auditing who consumes /me/plans contact groups).
 *
 * <p>The ids are sequential and the detail snapshot carries each contact's
 * phone, email, address and medical notes, so an unchecked id was a way to read
 * another family's contacts back through your own activation. Unreadable ids
 * are dropped, never rejected — the activation itself must still go out.</p>
 */
class PlanActivationContactGroupGuardTest {

    static final String OWNER = "owner@x.com";
    static final String HH = "hh-1";

    final PlanActivationRepo activationRepo = mock(PlanActivationRepo.class);
    final EmergencyContactGroupRepo groups = mock(EmergencyContactGroupRepo.class);
    final HouseholdAccessService access = mock(HouseholdAccessService.class);
    final UserInfoRepo userInfoRepo = mock(UserInfoRepo.class);
    PlanActivationService service;

    @BeforeEach
    void setUp() {
        service = new PlanActivationService(activationRepo, mock(PlanActivationAckRepo.class), userInfoRepo,
                mock(MeetingPlaceRepo.class), mock(EvacuationPlanRepo.class), mock(OriginLocationRepo.class),
                groups, mock(EmergencyContactRepo.class), mock(WebSocketMessageSender.class), mock(GroupRepo.class),
                mock(NotificationService.class), access,
                mock(HouseholdResolver.class), mock(GoBagService.class), mock(HouseholdEventService.class),
                mock(GroupService.class), mock(ActivationDirectiveResolver.class));
        when(userInfoRepo.findByUserEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(activationRepo.save(any())).thenAnswer(inv -> {
            PlanActivation p = inv.getArgument(0);
            p.setId("act-1");
            return p;
        });
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    static EmergencyContactGroup group(long id, String owner, String householdId) {
        EmergencyContactGroup g = new EmergencyContactGroup();
        g.setId(id);
        g.setOwnerEmail(owner);
        g.setHouseholdId(householdId);
        return g;
    }

    @Test
    void keepsOwnAndHouseholdGroups_dropsAnotherFamilysWithoutFailing() {
        when(groups.findAllById(any())).thenReturn(List.of(
                group(1, OWNER, null),                    // my own legacy group
                group(2, "spouse@x.com", HH),             // household group another member wrote
                group(3, "cousin@x.com", null),           // a co-member in another shared household
                group(4, "victim@x.com", "hh-victim")));  // someone else's family
        when(access.canReadPlanDataFor(OWNER, OWNER)).thenReturn(true);
        when(access.canReadPlanDataFor(OWNER, "cousin@x.com")).thenReturn(true);
        when(access.canReadHousehold(OWNER, HH)).thenReturn(true);

        service.createActivation(new CreateActivationRequest(OWNER, null, null, null, null, null, null, null, null,
                null, new RecipientsRequest(null, null, List.of(1L, 2L, 3L, 4L, 4L)), null));

        ArgumentCaptor<PlanActivation> saved = ArgumentCaptor.forClass(PlanActivation.class);
        verify(activationRepo).save(saved.capture());
        assertThat(saved.getValue().getContactGroupIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void anUnknownIdIsDroppedToo() {
        when(groups.findAllById(any())).thenReturn(List.of());
        service.createActivation(new CreateActivationRequest(OWNER, null, null, null, null, null, null, null, null,
                null, new RecipientsRequest(null, null, List.of(999L)), null));
        ArgumentCaptor<PlanActivation> saved = ArgumentCaptor.forClass(PlanActivation.class);
        verify(activationRepo).save(saved.capture());
        assertThat(saved.getValue().getContactGroupIds()).isEmpty();
    }
}
