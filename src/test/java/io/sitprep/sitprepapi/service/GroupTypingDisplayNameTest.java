package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupTypingFrame;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The typing frame must never carry an email address in its display-name slot.
 *
 * <p>It used to. {@code displayName()} ended {@code .orElse(email)}, and the
 * frame renders as "{name} is typing…" to every member of the group — so any
 * member who had not set a name had their address broadcast to the room. The
 * group surfaces are explicit ({@code canViewEmail} in {@code Members.js},
 * {@code ManageMembersModal.js}) that a plain member is not entitled to a
 * peer's address.</p>
 *
 * <p>This is pinned on the SERVER because the client cannot tell the two apart:
 * a {@code displayName} that looks like an address is either a real name or
 * this fallback having fired, and guessing between them is the brittle
 * client-side compensation the backend/frontend split exists to prevent. Absence
 * ships as absence; the client renders "Someone".</p>
 */
@ExtendWith(MockitoExtension.class)
class GroupTypingDisplayNameTest {

    @Mock GroupRepo groupRepo;
    @Mock UserInfoRepo userInfoRepo;
    @Mock WebSocketMessageSender ws;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void namedMemberIsBroadcastByName() {
        assertThat(relayAndCapture(userNamed("Maya", "Vega")).displayName()).isEqualTo("Maya Vega");
    }

    @Test
    void memberWithNoNameOnRecordIsBroadcastAsAbsent() {
        assertThat(relayAndCapture(userNamed(null, null)).displayName()).isNull();
    }

    /**
     * A blank name is the same as no name — the old chain's {@code filter} caught
     * this and then handed it to {@code orElse(email)}, so whitespace in a
     * profile was one of the ways an address reached the room.
     */
    @Test
    void blankNameIsAbsentToo() {
        assertThat(relayAndCapture(userNamed("   ", "  ")).displayName()).isNull();
    }

    /** The unnamed case must not leak the address through any other slot a client draws. */
    @Test
    void noProfileAtAllStillCarriesTheEmailOnlyAsTheIdentityKey() {
        GroupTypingFrame frame = relayAndCapture(null);
        assertThat(frame.displayName()).isNull();
        assertThat(frame.email()).isEqualTo("bc@probe.app");
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private UserInfo userNamed(String first, String last) {
        UserInfo u = new UserInfo();
        u.setUserEmail("bc@probe.app");
        u.setUserFirstName(first);
        u.setUserLastName(last);
        return u;
    }

    private GroupTypingFrame relayAndCapture(UserInfo profile) {
        Group group = new Group();
        group.setGroupId("g-hoa");
        group.setMemberEmails(List.of("bc@probe.app"));
        when(groupRepo.findByGroupId("g-hoa")).thenReturn(Optional.of(group));
        when(userInfoRepo.findByUserEmail("bc@probe.app")).thenReturn(Optional.ofNullable(profile));

        GroupTypingService service = new GroupTypingService(groupRepo, userInfoRepo, ws);

        // relay() registers an afterCommit callback, so the test has to stand in
        // for the transaction it normally runs inside.
        TransactionSynchronizationManager.initSynchronization();
        service.relay("g-hoa", "BC@Probe.app", true);
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ws).sendGroupTyping(org.mockito.ArgumentMatchers.eq("g-hoa"), captor.capture());
        return (GroupTypingFrame) captor.getValue();
    }
}
