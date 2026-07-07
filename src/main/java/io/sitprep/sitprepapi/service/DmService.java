package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.DmMessage;
import io.sitprep.sitprepapi.domain.DmThread;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.DmDtos.DmMessageDto;
import io.sitprep.sitprepapi.dto.DmDtos.DmThreadDto;
import io.sitprep.sitprepapi.dto.DmDtos.PeerDto;
import io.sitprep.sitprepapi.dto.DtoImages;
import io.sitprep.sitprepapi.repo.DmMessageRepo;
import io.sitprep.sitprepapi.repo.DmThreadRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Direct messages — 1:1 threads between two users.
 *
 * <p>Flow: {@code send} finds-or-creates the pair's single thread
 * (participants stored lowercase + lexicographically ordered), appends
 * the message, bumps the thread's {@code lastMessageAt} and the
 * sender's own read watermark, then broadcasts the message DTO on BOTH
 * participants' STOMP topics ({@code /topic/dm/{email}}) and dispatches
 * a presence-aware {@code DIRECT_MESSAGE} notification (Lane A —
 * interruptive like any messenger, but quiet-hours respecting; a DM is
 * not life-safety).</p>
 *
 * <p>Blocks are absolute: any block in either direction 403s the send.
 * Reads (inbox / thread) are participant-gated.</p>
 */
@Service
public class DmService {

    private static final Logger log = LoggerFactory.getLogger(DmService.class);
    private static final int BODY_MAX = 4000;

    private final DmThreadRepo threadRepo;
    private final DmMessageRepo messageRepo;
    private final UserInfoRepo userInfoRepo;
    private final BlockService blockService;
    private final WebSocketMessageSender webSocketMessageSender;
    private final NotificationService notificationService;

    public DmService(DmThreadRepo threadRepo,
                     DmMessageRepo messageRepo,
                     UserInfoRepo userInfoRepo,
                     BlockService blockService,
                     WebSocketMessageSender webSocketMessageSender,
                     NotificationService notificationService) {
        this.threadRepo = threadRepo;
        this.messageRepo = messageRepo;
        this.userInfoRepo = userInfoRepo;
        this.blockService = blockService;
        this.webSocketMessageSender = webSocketMessageSender;
        this.notificationService = notificationService;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<DmThreadDto> inbox(String viewerEmail) {
        String viewer = normalize(viewerEmail);
        return threadRepo.findInbox(viewer).stream()
                .map(t -> toThreadDto(t, viewer))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DmMessageDto> messages(Long threadId, String viewerEmail) {
        DmThread thread = requireParticipant(threadId, normalize(viewerEmail));
        return messageRepo.findByThreadIdOrderByCreatedAtAsc(thread.getId())
                .stream()
                .map(DmService::toMessageDto)
                .toList();
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Transactional
    public DmMessageDto send(String senderEmail, String peerEmailRaw, String bodyRaw) {
        String sender = normalize(senderEmail);
        String peer = normalize(peerEmailRaw);
        String body = bodyRaw == null ? "" : bodyRaw.trim();

        if (peer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "peerEmail is required");
        }
        if (peer.equals(sender)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot message yourself");
        }
        if (body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message body is required");
        }
        if (body.length() > BODY_MAX) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Message exceeds " + BODY_MAX + " characters");
        }
        if (blockService.isAnyBlock(sender, peer)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Messaging is unavailable between these accounts");
        }

        Instant now = Instant.now();
        DmThread thread = findOrCreateThread(sender, peer);

        DmMessage message = new DmMessage();
        message.setThreadId(thread.getId());
        message.setSenderEmail(sender);
        message.setBody(body);
        message.setCreatedAt(now);
        message = messageRepo.save(message);

        thread.setLastMessageAt(now);
        // Sending implies having read the thread up to your own message.
        if (sender.equals(thread.getParticipantAEmail())) {
            thread.setALastReadAt(now);
        } else {
            thread.setBLastReadAt(now);
        }
        threadRepo.save(thread);

        DmMessageDto dto = toMessageDto(message);
        webSocketMessageSender.sendDmMessage(thread.getParticipantAEmail(), dto);
        webSocketMessageSender.sendDmMessage(thread.getParticipantBEmail(), dto);
        dispatchDmNotification(sender, peer, body, thread.getId());
        return dto;
    }

    @Transactional
    public void markRead(Long threadId, String viewerEmail) {
        String viewer = normalize(viewerEmail);
        DmThread thread = requireParticipant(threadId, viewer);
        if (viewer.equals(thread.getParticipantAEmail())) {
            thread.setALastReadAt(Instant.now());
        } else {
            thread.setBLastReadAt(Instant.now());
        }
        threadRepo.save(thread);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private DmThread findOrCreateThread(String sender, String peer) {
        String a = sender.compareTo(peer) < 0 ? sender : peer;
        String b = sender.compareTo(peer) < 0 ? peer : sender;
        Optional<DmThread> existing =
                threadRepo.findByParticipantAEmailAndParticipantBEmail(a, b);
        if (existing.isPresent()) return existing.get();

        DmThread thread = new DmThread();
        thread.setParticipantAEmail(a);
        thread.setParticipantBEmail(b);
        try {
            return threadRepo.save(thread);
        } catch (DataIntegrityViolationException race) {
            // Two first-messages raced — the unique constraint held; reuse
            // the winner's row.
            return threadRepo.findByParticipantAEmailAndParticipantBEmail(a, b)
                    .orElseThrow(() -> race);
        }
    }

    private DmThread requireParticipant(Long threadId, String viewer) {
        DmThread thread = threadRepo.findById(threadId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Thread not found"));
        boolean participant = viewer.equals(thread.getParticipantAEmail())
                || viewer.equals(thread.getParticipantBEmail());
        if (!participant) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Not a participant in this thread");
        }
        return thread;
    }

    private DmThreadDto toThreadDto(DmThread thread, String viewer) {
        boolean viewerIsA = viewer.equals(thread.getParticipantAEmail());
        String peerEmail = viewerIsA
                ? thread.getParticipantBEmail()
                : thread.getParticipantAEmail();
        Instant watermark = viewerIsA ? thread.getALastReadAt() : thread.getBLastReadAt();

        DmMessageDto last = messageRepo
                .findFirstByThreadIdOrderByCreatedAtDesc(thread.getId())
                .map(DmService::toMessageDto)
                .orElse(null);

        long unread = watermark == null
                ? messageRepo.countByThreadIdAndSenderEmailNot(thread.getId(), viewer)
                : messageRepo.countByThreadIdAndSenderEmailNotAndCreatedAtAfter(
                        thread.getId(), viewer, watermark);

        return new DmThreadDto(
                thread.getId(), peerDtoFor(peerEmail), last, unread, watermark);
    }

    private PeerDto peerDtoFor(String peerEmail) {
        UserInfo peer = userInfoRepo.findByUserEmailIgnoreCase(peerEmail).orElse(null);
        if (peer == null) {
            return new PeerDto(null, peerEmail, peerEmail, null);
        }
        String name = ((peer.getUserFirstName() == null ? "" : peer.getUserFirstName())
                + " "
                + (peer.getUserLastName() == null ? "" : peer.getUserLastName())).trim();
        return new PeerDto(
                peer.getId(),
                peerEmail,
                name.isBlank() ? peerEmail : name,
                DtoImages.avatar(peer.getProfileImageUrl()));
    }

    /**
     * Presence-aware DIRECT_MESSAGE dispatch — push when the recipient is
     * away, banner when they're in-app; the STOMP frame above already
     * carries the live message to an open sheet.
     */
    private void dispatchDmNotification(String sender, String peer, String body, Long threadId) {
        try {
            Optional<UserInfo> recipientOpt = userInfoRepo.findByUserEmailIgnoreCase(peer);
            if (recipientOpt.isEmpty()) return;
            UserInfo recipient = recipientOpt.get();

            UserInfo actor = userInfoRepo.findByUserEmailIgnoreCase(sender).orElse(null);
            String actorName = actor == null ? sender
                    : (((actor.getUserFirstName() == null ? "" : actor.getUserFirstName())
                        + " "
                        + (actor.getUserLastName() == null ? "" : actor.getUserLastName()))
                        .trim());
            if (actorName.isBlank()) actorName = sender;
            String actorIcon = actor != null ? DtoImages.avatar(actor.getProfileImageUrl()) : null;
            String actorIdentifier = actor != null && actor.getId() != null
                    ? actor.getId()
                    : sender;
            String preview = body.length() > 120 ? body.substring(0, 117) + "…" : body;

            notificationService.deliverPresenceAware(
                    recipient.getUserEmail(),
                    actorName,
                    preview,
                    actorName,
                    actorIcon,
                    "dm_message",
                    String.valueOf(threadId),
                    // No standalone /messages route — the sender's profile
                    // carries the Message entry point that reopens the thread.
                    "/profile/" + actorIdentifier,
                    null,
                    recipient.getFcmtoken(),
                    Category.DIRECT_MESSAGE,
                    actor != null ? actor.getId() : null
            );
        } catch (Exception e) {
            // Notification delivery must never fail the send itself.
            log.warn("DM notification dispatch failed for thread {}: {}", threadId, e.getMessage());
        }
    }

    private static DmMessageDto toMessageDto(DmMessage m) {
        return new DmMessageDto(
                m.getId(), m.getThreadId(), m.getSenderEmail(), m.getBody(), m.getCreatedAt());
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
