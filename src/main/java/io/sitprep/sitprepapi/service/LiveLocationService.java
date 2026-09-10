package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.constant.LocationSharing;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.LiveLocationPoint;
import io.sitprep.sitprepapi.domain.LiveLocationSession;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.LiveLocationDtos.LiveLocationFrame;
import io.sitprep.sitprepapi.dto.LiveLocationDtos.LiveLocationMemberDto;
import io.sitprep.sitprepapi.dto.LiveLocationDtos.LiveLocationPointRequest;
import io.sitprep.sitprepapi.dto.LiveLocationDtos.LiveLocationSessionDto;
import io.sitprep.sitprepapi.dto.LiveLocationDtos.StartLiveLocationSessionRequest;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.LiveLocationPointRepo;
import io.sitprep.sitprepapi.repo.LiveLocationSessionRepo;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.GeoUtil;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class LiveLocationService {

    private static final int DEFAULT_DURATION_MINUTES = 240;
    private static final int MIN_DURATION_MINUTES = 5;
    private static final int MAX_DURATION_MINUTES = 24 * 60;
    private static final Duration STALE_POINT_WINDOW = Duration.ofMinutes(15);
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

    private final LiveLocationSessionRepo sessionRepo;
    private final LiveLocationPointRepo pointRepo;
    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final PlanActivationRepo activationRepo;
    private final HouseholdResolver householdResolver;
    private final WebSocketMessageSender ws;

    public LiveLocationService(LiveLocationSessionRepo sessionRepo,
                               LiveLocationPointRepo pointRepo,
                               GroupRepo groupRepo,
                               UserInfoRepo userInfoRepo,
                               PlanActivationRepo activationRepo,
                               HouseholdResolver householdResolver,
                               WebSocketMessageSender ws) {
        this.sessionRepo = sessionRepo;
        this.pointRepo = pointRepo;
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.activationRepo = activationRepo;
        this.householdResolver = householdResolver;
        this.ws = ws;
    }

    @Transactional
    public LiveLocationSessionDto start(String email, StartLiveLocationSessionRequest request) {
        String actor = normalizeEmail(email);
        UserInfo user = userInfoRepo.findByUserEmailIgnoreCase(actor)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        PlanActivation activation = activeActivationOrNull(request == null ? null : request.activationId());
        Set<String> groupIds = sanitizeGroupIds(request == null ? null : request.groupIds());
        if (groupIds.isEmpty() && activation != null) {
            String householdId = householdResolver.baseHouseholdIdFor(activation.getOwnerEmail());
            if (householdId != null && !householdId.isBlank()) {
                groupIds.add(householdId);
            }
        }
        if (groupIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one group is required");
        }

        boolean activationContext = activation != null;
        List<Group> groups = groupIds.stream()
                .map(groupId -> requireShareableGroup(groupId, actor, user, activationContext))
                .toList();
        Instant now = Instant.now();

        LiveLocationSession session = new LiveLocationSession();
        session.setUserEmail(actor);
        session.setScopeType("group");
        session.setGroupIds(new LinkedHashSet<>(groupIds));
        session.setActivationId(blankToNull(request == null ? null : request.activationId()));
        session.setAlertId(blankToNull(request == null ? null : request.alertId()));
        session.setStartedAt(now);
        session.setExpiresAt(now.plus(Duration.ofMinutes(durationMinutes(request))));
        session.setCreatedByUser(true);
        String uploadToken = generateUploadToken();
        session.setUploadTokenHash(hashUploadToken(uploadToken));

        LiveLocationSession saved = sessionRepo.save(session);
        afterCommit(() -> groups.forEach(group -> ws.sendGroupMemberLocation(
                group.getGroupId(),
                new LiveLocationFrame(
                        "live-location",
                        saved.getId(),
                        actor,
                        null,
                        null,
                        null,
                        null,
                        null,
                        saved.getStartedAt(),
                        saved.getExpiresAt(),
                        true,
                        false
                )
        )));
        return toDto(saved, uploadToken);
    }

    @Transactional
    public LiveLocationMemberDto updatePoint(String email, String sessionId, LiveLocationPointRequest request) {
        String actor = normalizeEmail(email);
        LiveLocationSession session = requireOwnedActiveSession(actor, sessionId);
        return updatePointForSession(session, request);
    }

    @Transactional
    public LiveLocationMemberDto updatePointWithUploadToken(String sessionId, String uploadToken,
                                                            LiveLocationPointRequest request) {
        LiveLocationSession session = requireActiveSessionByUploadToken(sessionId, uploadToken);
        return updatePointForSession(session, request);
    }

    private LiveLocationMemberDto updatePointForSession(LiveLocationSession session,
                                                        LiveLocationPointRequest request) {
        if (request == null || request.lat() == null || request.lng() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lat and lng are required");
        }
        GeoUtil.requireValidLatLng(request.lat(), request.lng());
        String actor = normalizeEmail(session.getUserEmail());

        LiveLocationPoint point = new LiveLocationPoint();
        point.setSessionId(session.getId());
        point.setUserEmail(actor);
        point.setLat(request.lat());
        point.setLng(request.lng());
        point.setAccuracyM(request.accuracyM());
        point.setSpeedMps(request.speedMps());
        point.setHeadingDeg(request.headingDeg());
        point.setCapturedAt(validCapturedAt(request.capturedAt()));
        LiveLocationPoint savedPoint = pointRepo.save(point);

        userInfoRepo.findByUserEmailIgnoreCase(actor).ifPresent(user -> {
            user.setLastKnownLat(savedPoint.getLat());
            user.setLastKnownLng(savedPoint.getLng());
            user.setLastKnownLocationAt(savedPoint.getCapturedAt());
            userInfoRepo.save(user);
        });

        LiveLocationMemberDto dto = toMemberDto(session, savedPoint);
        LiveLocationFrame frame = toFrame(session, savedPoint, true, false);
        afterCommit(() -> visibleGroupsForSession(session, actor).forEach(group ->
                ws.sendGroupMemberLocation(group.getGroupId(), frame)));
        return dto;
    }

    @Transactional
    public LiveLocationSessionDto stop(String email, String sessionId) {
        String actor = normalizeEmail(email);
        LiveLocationSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Live location session not found"));
        if (!actor.equals(normalizeEmail(session.getUserEmail()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only stop your own live location");
        }
        if (session.getStoppedAt() == null) {
            session.setStoppedAt(Instant.now());
            session = sessionRepo.save(session);
        }
        LiveLocationSession stopped = session;
        afterCommit(() -> memberGroupsForSession(stopped, actor).forEach(group ->
                ws.sendGroupMemberLocation(group.getGroupId(), new LiveLocationFrame(
                        "live-location",
                        stopped.getId(),
                        actor,
                        null,
                        null,
                        null,
                        null,
                        null,
                        stopped.getStoppedAt(),
                        stopped.getExpiresAt(),
                        false,
                        true
                ))));
        return toDto(stopped);
    }

    @Transactional
    public LiveLocationSessionDto stopWithUploadToken(String sessionId, String uploadToken) {
        LiveLocationSession session = requireActiveSessionByUploadToken(sessionId, uploadToken);
        return stopSession(session);
    }

    private LiveLocationSessionDto stopSession(LiveLocationSession session) {
        String actor = normalizeEmail(session.getUserEmail());
        if (session.getStoppedAt() == null) {
            session.setStoppedAt(Instant.now());
            session = sessionRepo.save(session);
        }
        LiveLocationSession stopped = session;
        afterCommit(() -> memberGroupsForSession(stopped, actor).forEach(group ->
                ws.sendGroupMemberLocation(group.getGroupId(), new LiveLocationFrame(
                        "live-location",
                        stopped.getId(),
                        actor,
                        null,
                        null,
                        null,
                        null,
                        null,
                        stopped.getStoppedAt(),
                        stopped.getExpiresAt(),
                        false,
                        true
                ))));
        return toDto(stopped);
    }

    @Transactional(readOnly = true)
    public List<LiveLocationMemberDto> listForGroup(String viewerEmail, String groupId) {
        String viewer = normalizeEmail(viewerEmail);
        Group group = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        if (GroupRole.fromGroup(group, viewer) == GroupRole.NONE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have access to this group");
        }

        Instant now = Instant.now();
        Instant freshAfter = now.minus(STALE_POINT_WINDOW);
        List<LiveLocationMemberDto> rows = new ArrayList<>();
        for (LiveLocationSession session : sessionRepo.findActiveForGroup(group.getGroupId(), now)) {
            if (!isVisibleToGroup(session, group)) continue;
            pointRepo.findTopBySessionIdOrderByCapturedAtDesc(session.getId())
                    .filter(point -> !point.getCapturedAt().isBefore(freshAfter))
                    .map(point -> toMemberDto(session, point))
                    .ifPresent(rows::add);
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public List<LiveLocationSessionDto> listMine(String email) {
        String actor = normalizeEmail(email);
        return sessionRepo.findTop25ByUserEmailIgnoreCaseOrderByStartedAtDesc(actor).stream()
                .map(this::toDto)
                .toList();
    }

    private Group requireShareableGroup(String groupId, String actor, UserInfo user, boolean activationContext) {
        Group group = groupRepo.findByGroupId(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        if (GroupRole.fromGroup(group, actor) == GroupRole.NONE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only share with your groups");
        }
        if (!LocationSharing.shouldShare(
                user.getGroupLocationSharing(),
                group.getGroupId(),
                group.getGroupType(),
                activationContext || isAlertActive(group))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your location sharing setting does not allow this group right now");
        }
        return group;
    }

    private PlanActivation activeActivationOrNull(String activationId) {
        String id = blankToNull(activationId);
        if (id == null) return null;
        PlanActivation activation = activationRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Activation not found"));
        if (activation.getEndedAt() != null || !activation.getExpiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Activation is no longer active");
        }
        return activation;
    }

    private LiveLocationSession requireOwnedActiveSession(String actor, String sessionId) {
        LiveLocationSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Live location session not found"));
        if (!actor.equals(normalizeEmail(session.getUserEmail()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only update your own live location");
        }
        Instant now = Instant.now();
        if (session.getStoppedAt() != null || !session.getExpiresAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Live location session is no longer active");
        }
        return session;
    }

    private LiveLocationSession requireActiveSessionByUploadToken(String sessionId, String uploadToken) {
        LiveLocationSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Live location session not found"));
        if (uploadToken == null || uploadToken.isBlank()
                || session.getUploadTokenHash() == null
                || !constantTimeEquals(session.getUploadTokenHash(), hashUploadToken(uploadToken))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Valid live location upload token required");
        }
        Instant now = Instant.now();
        if (session.getStoppedAt() != null || !session.getExpiresAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Live location session is no longer active");
        }
        return session;
    }

    private List<Group> visibleGroupsForSession(LiveLocationSession session, String actor) {
        UserInfo user = userInfoRepo.findByUserEmailIgnoreCase(actor).orElse(null);
        if (user == null) return List.of();
        return session.getGroupIds().stream()
                .map(groupRepo::findByGroupId)
                .flatMap(java.util.Optional::stream)
                .filter(group -> GroupRole.fromGroup(group, actor) != GroupRole.NONE)
                .filter(group -> LocationSharing.shouldShare(
                        user.getGroupLocationSharing(),
                        group.getGroupId(),
                        group.getGroupType(),
                        isAlertActive(group)))
                .toList();
    }

    private List<Group> memberGroupsForSession(LiveLocationSession session, String actor) {
        return session.getGroupIds().stream()
                .map(groupRepo::findByGroupId)
                .flatMap(java.util.Optional::stream)
                .filter(group -> GroupRole.fromGroup(group, actor) != GroupRole.NONE)
                .toList();
    }

    private boolean isVisibleToGroup(LiveLocationSession session, Group group) {
        UserInfo user = userInfoRepo.findByUserEmailIgnoreCase(session.getUserEmail()).orElse(null);
        return user != null && LocationSharing.shouldShare(
                user.getGroupLocationSharing(),
                group.getGroupId(),
                group.getGroupType(),
                isAlertActive(group));
    }

    private LiveLocationSessionDto toDto(LiveLocationSession session) {
        return toDto(session, null);
    }

    private LiveLocationSessionDto toDto(LiveLocationSession session, String uploadToken) {
        return new LiveLocationSessionDto(
                session.getId(),
                normalizeEmail(session.getUserEmail()),
                List.copyOf(session.getGroupIds()),
                session.getActivationId(),
                session.getAlertId(),
                session.getStartedAt(),
                session.getExpiresAt(),
                session.getStoppedAt(),
                uploadToken
        );
    }

    private LiveLocationMemberDto toMemberDto(LiveLocationSession session, LiveLocationPoint point) {
        return new LiveLocationMemberDto(
                session.getId(),
                normalizeEmail(session.getUserEmail()),
                point.getLat(),
                point.getLng(),
                point.getAccuracyM(),
                point.getSpeedMps(),
                point.getHeadingDeg(),
                point.getCapturedAt(),
                session.getExpiresAt()
        );
    }

    private LiveLocationFrame toFrame(LiveLocationSession session, LiveLocationPoint point,
                                      boolean active, boolean stopped) {
        return new LiveLocationFrame(
                "live-location",
                session.getId(),
                normalizeEmail(session.getUserEmail()),
                point.getLat(),
                point.getLng(),
                point.getAccuracyM(),
                point.getSpeedMps(),
                point.getHeadingDeg(),
                point.getCapturedAt(),
                session.getExpiresAt(),
                active,
                stopped
        );
    }

    private Set<String> sanitizeGroupIds(List<String> rawGroupIds) {
        Set<String> ids = new LinkedHashSet<>();
        if (rawGroupIds == null) return ids;
        rawGroupIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .forEach(ids::add);
        return ids;
    }

    private int durationMinutes(StartLiveLocationSessionRequest request) {
        Integer raw = request == null ? null : request.durationMinutes();
        if (raw == null) return DEFAULT_DURATION_MINUTES;
        return Math.max(MIN_DURATION_MINUTES, Math.min(MAX_DURATION_MINUTES, raw));
    }

    private Instant validCapturedAt(Instant capturedAt) {
        Instant now = Instant.now();
        if (capturedAt == null) return now;
        if (capturedAt.isAfter(now.plus(Duration.ofMinutes(2)))) {
            return now;
        }
        return capturedAt;
    }

    private static boolean isAlertActive(Group group) {
        return group != null && "active".equalsIgnoreCase(group.getAlert());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String generateUploadToken() {
        byte[] bytes = new byte[32];
        TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashUploadToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated request required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static void afterCommit(Runnable runnable) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    runnable.run();
                }
            });
        } else {
            runnable.run();
        }
    }
}
