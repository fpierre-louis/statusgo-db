package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.StartCheck;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;

/**
 * Together sessions (redesign FR-8 / BE-09): resumable position in an adult-led activity.
 * A session holds the household, activity, style and turn index and nothing about who is
 * playing. Any household member may start or advance one (the adults hand the phone around).
 */
@Service
public class TogetherSessionService {

    static final Duration TTL = Duration.ofHours(24);

    public record SessionDto(long sessionId, String activityKey, int contentVersion, String style,
                             int turnIndex, Instant expiresAt) {}

    private final PracticeAvailabilityService availability;
    private final PracticeSuppressionService suppression;
    private final PracticeTogetherSessionRepo repo;
    private final GroupRepo groupRepo;
    private final HouseholdAccessService access;
    private final Clock clock;

    @Autowired
    public TogetherSessionService(PracticeAvailabilityService availability, PracticeSuppressionService suppression,
                                  PracticeTogetherSessionRepo repo, GroupRepo groupRepo, HouseholdAccessService access) {
        this(availability, suppression, repo, groupRepo, access, Clock.systemUTC());
    }

    TogetherSessionService(PracticeAvailabilityService availability, PracticeSuppressionService suppression,
                           PracticeTogetherSessionRepo repo, GroupRepo groupRepo, HouseholdAccessService access,
                           Clock clock) {
        this.availability = availability;
        this.suppression = suppression;
        this.repo = repo;
        this.groupRepo = groupRepo;
        this.access = access;
        this.clock = clock;
    }

    /** Starts a session at turn 0, replacing the household's open session for the activity. */
    @Transactional
    public SessionDto create(String callerEmail, String householdId, String activityKey, String style) {
        String email = normalize(callerEmail);
        if (householdId == null || householdId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "householdId is required");
        }
        if (activityKey == null || activityKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "activityKey is required");
        }
        if (style == null || Arrays.stream(TogetherStyle.values()).noneMatch(s -> s.name().equals(style))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "style must be DO_IT_TOGETHER, TAKE_A_TURN or TEEN_LEAD");
        }
        groupRepo.findByGroupId(householdId)
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found"));
        access.requireCanReadHousehold(email, householdId);
        requireCalm(householdId, email);

        Version v = startableTogether(activityKey);
        Instant now = clock.instant();
        repo.deleteByHouseholdAndActivity(householdId, v.key());
        PracticeTogetherSession s = new PracticeTogetherSession();
        s.setHouseholdId(householdId);
        s.setActivityKey(v.key());
        s.setContentVersion(v.version());
        s.setStyle(style);
        s.setTurnIndex(0);
        s.setStartedAt(now);
        s.setUpdatedAt(now);
        s.setExpiresAt(now.plus(TTL));
        return dto(repo.save(s));
    }

    /** Moves the session to {@code turnIndex} (0..turns; turns itself is the end screen). */
    @Transactional
    public SessionDto turn(String callerEmail, long sessionId, Integer turnIndex) {
        String email = normalize(callerEmail);
        if (turnIndex == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "turnIndex is required");
        PracticeTogetherSession s = repo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        access.requireCanReadHousehold(email, s.getHouseholdId());
        requireCalm(s.getHouseholdId(), email);
        Instant now = clock.instant();
        if (!s.getExpiresAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.GONE, "This session has ended");
        }
        Version v = startableTogether(s.getActivityKey());
        if (v.version() != s.getContentVersion()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This session has ended");
        }
        int turns = v.body().activity().turns().size();
        if (turnIndex < 0 || turnIndex > turns) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "turnIndex must be between 0 and " + turns);
        }
        s.setTurnIndex(turnIndex);
        s.setUpdatedAt(now);
        return dto(repo.save(s));
    }

    private Version startableTogether(String activityKey) {
        StartCheck start = availability.checkStart(activityKey);
        if (!start.allowed()) {
            switch (start.denial()) {
                case DISABLED, PRACTICE_OFF -> throw new ResponseStatusException(HttpStatus.GONE, ScenarioService.WITHDRAWN);
                default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Together activity not found");
            }
        }
        Version v = start.entry().version();
        if (v.kind() != PracticeKind.FAMILY_ACTIVITY || v.body().activity() == null
                || !"TOGETHER".equals(v.body().activity().type())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Together activity not found");
        }
        return v;
    }

    private void requireCalm(String hid, String email) {
        if (suppression.check(hid, email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ScenarioService.WAITING);
        }
    }

    private static SessionDto dto(PracticeTogetherSession s) {
        return new SessionDto(s.getId(), s.getActivityKey(), s.getContentVersion(), s.getStyle(),
                s.getTurnIndex(), s.getExpiresAt());
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
