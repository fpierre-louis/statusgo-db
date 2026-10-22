package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.DrillCompletion;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.SkillStateDto;
import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.SkillStatesDto;
import io.sitprep.sitprepapi.practice.PracticeContent.Skill;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a household has actually done with each startable skill (Practice redesign
 * BE-06): {@code NEW}, {@code PRACTICED} or {@code REFRESH}, from facts the server
 * already holds, never from a skill alone and never as a score.
 *
 * <ul>
 *   <li><b>Exposure</b>: the newest COMPLETED run of a scenario named in
 *       {@code skill.scenarioNodes}, or the newest mark of a drill in
 *       {@code skill.drills}. Read where it already lives; nothing is copied.</li>
 *   <li><b>Practiced</b>: exposure or the last check-in, except a skill whose physical
 *       rehearsal is REQUIRED, which counts drills only (a story or a recall answer
 *       does not make a physical skill practiced).</li>
 *   <li><b>Due</b>: the state row's {@code next_due_at}; with no row, exposure plus
 *       {@link #FIRST_DUE} (computed on read, so exposure itself writes nothing).
 *       No exposure and no row: never due.</li>
 * </ul>
 *
 * <p>Read-only: the only writer of {@code practice_skill_state} is
 * {@link PracticeCheckInService}.</p>
 */
@Service
public class PracticeSkillStateService {

    static final Duration FIRST_DUE = Duration.ofDays(2);

    /** One startable skill's standing for one household. {@code dueAt} null = never due. */
    public record Standing(String skillKey, String hazard, Instant practicedAt, Instant dueAt) {
        boolean dueBy(Instant now) { return dueAt != null && !dueAt.isAfter(now); }
    }

    private final PracticeAvailabilityService availability;
    private final PracticeSkillStateRepo stateRepo;
    private final ScenarioRunRepo runRepo;
    private final GroupRepo groupRepo;
    private final HouseholdAccessService access;
    private final Clock clock;

    @Autowired
    public PracticeSkillStateService(PracticeAvailabilityService availability, PracticeSkillStateRepo stateRepo,
                                     ScenarioRunRepo runRepo, GroupRepo groupRepo, HouseholdAccessService access) {
        this(availability, stateRepo, runRepo, groupRepo, access, Clock.systemUTC());
    }

    PracticeSkillStateService(PracticeAvailabilityService availability, PracticeSkillStateRepo stateRepo,
                              ScenarioRunRepo runRepo, GroupRepo groupRepo, HouseholdAccessService access, Clock clock) {
        this.availability = availability;
        this.stateRepo = stateRepo;
        this.runRepo = runRepo;
        this.groupRepo = groupRepo;
        this.access = access;
        this.clock = clock;
    }

    /** A household endpoint: required (400), real (404), readable by the caller (403). */
    String requireHousehold(String callerEmail, String householdId) {
        if (householdId == null || householdId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "householdId is required");
        }
        groupRepo.findByGroupId(householdId)
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found"));
        access.requireCanReadHousehold(callerEmail, householdId);
        return householdId;
    }

    /**
     * {@code GET /api/practice/skills/state}. {@code withCheckIn} are the skills a refresh
     * could actually be served for: REFRESH is only said when there is something to do.
     */
    @Transactional(readOnly = true)
    public SkillStatesDto states(String callerEmail, String householdId, Set<String> withCheckIn) {
        String hid = requireHousehold(callerEmail, householdId);
        Instant now = clock.instant();
        List<SkillStateDto> out = new ArrayList<>();
        for (Standing s : standings(hid)) {
            String status = s.practicedAt() == null ? "NEW"
                    : withCheckIn.contains(s.skillKey()) && s.dueBy(now) ? "REFRESH" : "PRACTICED";
            out.add(new SkillStateDto(s.skillKey(), status, s.practicedAt()));
        }
        return new SkillStatesDto(PracticeCheckInDtos.SCHEMA_VERSION, out);
    }

    /** Every startable skill's standing for {@code hid}, in stable key order. */
    @Transactional(readOnly = true)
    public List<Standing> standings(String hid) {
        List<PracticeAvailabilityService.Entry> skills = availability.startable(PracticeKind.SKILL);
        if (skills.isEmpty()) return List.of();

        Map<String, Instant> lastCompletedRun = new HashMap<>();
        for (ScenarioRun r : runRepo.findByHouseholdIdOrderByStartedAtDesc(hid)) {
            if (r.getStatus() != ScenarioRun.Status.COMPLETED || r.getCompletedAt() == null) continue;
            lastCompletedRun.merge(r.getScenarioKey(), r.getCompletedAt(), PracticeSkillStateService::later);
        }
        Map<String, DrillCompletion> drillLog = groupRepo.findByGroupId(hid)
                .map(Group::getDrillLog).orElse(Map.of());
        Map<String, PracticeSkillState> states = new HashMap<>();
        for (PracticeSkillState st : stateRepo.findByHouseholdId(hid)) states.put(st.getSkillKey(), st);

        List<Standing> out = new ArrayList<>();
        for (PracticeAvailabilityService.Entry e : skills) {
            Version v = e.version();
            Skill k = v.body().skill();
            if (k == null) continue;
            Instant scenarioExposure = null;
            if (k.scenarioNodes() != null) for (String ref : k.scenarioNodes()) {
                int hash = ref.indexOf('#');
                scenarioExposure = later(scenarioExposure, lastCompletedRun.get(hash < 0 ? ref : ref.substring(0, hash)));
            }
            Instant drillExposure = null;
            if (k.drills() != null) for (String d : k.drills()) {
                for (Map.Entry<String, DrillCompletion> m : drillLog.entrySet()) {
                    String key = m.getKey();
                    if (m.getValue() == null || key == null) continue;
                    if (key.equals(d) || key.startsWith(d + "#")) drillExposure = later(drillExposure, m.getValue().getCompletedAt());
                }
            }
            Instant exposure = later(scenarioExposure, drillExposure);
            PracticeSkillState st = states.get(v.key());

            Instant practiced = k.physicalRehearsal() == PracticeContent.PhysicalRehearsal.REQUIRED
                    ? drillExposure
                    : later(exposure, st == null ? null : st.getLastSeenAt());
            Instant due = st != null ? st.getNextDueAt() : exposure == null ? null : exposure.plus(FIRST_DUE);
            out.add(new Standing(v.key(), k.hazard(), practiced, due));
        }
        return out;
    }

    private static Instant later(Instant a, Instant b) {
        if (b == null) return a;
        return a == null || b.isAfter(a) ? b : a;
    }
}
