package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto;
import io.sitprep.sitprepapi.readiness.ActiveResponseResolver;
import io.sitprep.sitprepapi.readiness.ActiveResponseResolver.ActiveResponse;
import io.sitprep.sitprepapi.readiness.ReadinessAction;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.ConcealmentSafetyService;
import io.sitprep.sitprepapi.service.RiskProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * Active response suppresses Practice: a rehearsal must never compete with
 * the real thing for a person's attention, or be mistaken for it.
 *
 * <p>The household half is {@link ActiveResponseResolver} — the same gate
 * Ready for More uses (plan activation, then household check-in, then an
 * official alert at home) — so "calm" means one thing across preparedness
 * surfaces. The caller half adds a lockdown/concealment event near the caller
 * ({@link ConcealmentSafetyService}), which also covers a caller with no
 * household.</p>
 *
 * <p><b>Fails closed.</b> If a signal cannot be read, Practice waits
 * ({@link Reason#UNVERIFIED}). Blocking an optional rehearsal costs a retry;
 * letting one run during an emergency we failed to detect is the harm this
 * gate exists to prevent.</p>
 *
 * <p>This service only <i>reads</i> emergency state. The one action it hands
 * back points the user OUT of Practice to the real surface.</p>
 */
@Service
public class PracticeSuppressionService {

    private static final Logger log = LoggerFactory.getLogger(PracticeSuppressionService.class);

    public enum Reason { PLAN_ACTIVATION, CHECK_IN, OFFICIAL_ALERT, LOCKDOWN, UNVERIFIED }

    /** Why Practice is waiting, in Practice's own words, plus where the real thing lives. */
    public record Suppression(Reason reason, String title, String detail,
                              ReadinessAction action, Map<String, String> params) {}

    static final String WAIT = "Practice can wait. Follow your plan first.";

    private final GroupRepo groupRepo;
    private final ActiveResponseResolver activeResponseResolver;
    private final RiskProfileService riskProfileService;
    private final UserInfoRepo userInfoRepo;
    private final ConcealmentSafetyService concealmentSafetyService;
    private final Clock clock;

    @Autowired
    public PracticeSuppressionService(GroupRepo groupRepo,
                                      ActiveResponseResolver activeResponseResolver,
                                      RiskProfileService riskProfileService,
                                      UserInfoRepo userInfoRepo,
                                      ConcealmentSafetyService concealmentSafetyService) {
        this(groupRepo, activeResponseResolver, riskProfileService, userInfoRepo, concealmentSafetyService,
                Clock.systemUTC());
    }

    PracticeSuppressionService(GroupRepo groupRepo, ActiveResponseResolver activeResponseResolver,
                               RiskProfileService riskProfileService, UserInfoRepo userInfoRepo,
                               ConcealmentSafetyService concealmentSafetyService, Clock clock) {
        this.groupRepo = groupRepo;
        this.activeResponseResolver = activeResponseResolver;
        this.riskProfileService = riskProfileService;
        this.userInfoRepo = userInfoRepo;
        this.concealmentSafetyService = concealmentSafetyService;
        this.clock = clock;
    }

    /**
     * Why Practice should wait for {@code callerEmail} (in {@code householdId},
     * when given), or empty when things are calm. Access to the household is
     * the caller's job; an unknown household simply contributes no signal.
     */
    @Transactional(readOnly = true)
    public Optional<Suppression> check(String householdId, String callerEmail) {
        if (householdId != null && !householdId.isBlank()) {
            Optional<Group> household = groupRepo.findByGroupId(householdId)
                    .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()));
            if (household.isPresent()) {
                Optional<Suppression> s = householdSignal(household.get());
                if (s.isPresent()) return s;
            }
        }
        return callerSignal(callerEmail);
    }

    private Optional<Suppression> householdSignal(Group household) {
        RiskProfileDto risk;
        ActiveResponse active;
        try {
            risk = riskProfileService.resolveFor(household);
            active = activeResponseResolver.resolve(household, risk, clock.instant());
        } catch (RuntimeException e) {
            log.warn("Practice suppression: household {} signal unreadable; practice waits", household.getGroupId(), e);
            return Optional.of(unverified());
        }
        if (active == null) return Optional.empty();
        Reason reason = switch (active.kind()) {
            case PLAN_ACTIVATION -> Reason.PLAN_ACTIVATION;
            case CHECK_IN -> Reason.CHECK_IN;
            case OFFICIAL_ALERT -> Reason.OFFICIAL_ALERT;
        };
        String title = switch (reason) {
            case PLAN_ACTIVATION -> "Your plan is active";
            case CHECK_IN -> "Your household is checking in";
            default -> "An official alert is in effect";
        };
        return Optional.of(new Suppression(reason, title, WAIT, active.action(),
                active.params() == null ? Map.of() : active.params()));
    }

    private Optional<Suppression> callerSignal(String callerEmail) {
        if (callerEmail == null || callerEmail.isBlank()) return Optional.empty();
        try {
            UserInfo user = userInfoRepo.findByUserEmailIgnoreCase(callerEmail).orElse(null);
            if (user != null && concealmentSafetyService.isConcealmentSensitiveFor(user)) {
                return Optional.of(new Suppression(Reason.LOCKDOWN, "A safety alert is in effect near you",
                        "Practice can wait. Follow official instructions first.",
                        ReadinessAction.OPEN_ACTIVE_ALERTS, Map.of()));
            }
        } catch (RuntimeException e) {
            log.warn("Practice suppression: caller signal unreadable; practice waits", e);
            return Optional.of(unverified());
        }
        return Optional.empty();
    }

    private static Suppression unverified() {
        return new Suppression(Reason.UNVERIFIED, "Practice is paused for a moment",
                "SitPrep can't confirm the current safety situation right now.",
                null, Map.of());
    }
}
