package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.Entry;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.StartCheck;
import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.AnswerDto;
import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.CardDto;
import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.DueDto;
import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.SkillStatesDto;
import io.sitprep.sitprepapi.practice.PracticeContent.CheckIn;
import io.sitprep.sitprepapi.practice.PracticeContent.CheckInChoice;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import io.sitprep.sitprepapi.practice.PracticeSkillStateService.Standing;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ChoiceDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.SuppressionDto;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Spaced recall (Practice redesign FR-7 / BE-05): up to three due check-ins for one
 * hazard, answered one at a time, each answer scheduling its skill's next one.
 *
 * <p><b>Doctrine.</b> No score, no streak, no correctness colour. Which choice is right
 * is internal: it steers scheduling and is stored on the answer, and no response
 * carries it. A not-chosen-right answer gets the fuller explanation, never a verdict.
 * The answer row has no actor. Like skills, check-in content is DRAFT until
 * independently reviewed, and production serves nothing until then (the same
 * {@link PracticeAvailabilityService} gate).</p>
 *
 * <p><b>Scheduling</b> (CHECKIN_COMPONENTS): ladder {@value #FIRST_INTERVAL} → 9 → 30 → 90
 * days. Correct with "Had it" or no confidence: next rung. Correct and "Partly": same
 * interval. Correct and "Not yet": half, never under 2. Incorrect: 2. A skill with no
 * state row is on the first rung, so its first correct answer goes to 9.</p>
 */
@Service
public class PracticeCheckInService {

    static final int FIRST_INTERVAL = 2;
    static final int[] LADDER = {2, 9, 30, 90};
    static final int MAX_CARDS = 3;

    public enum Confidence { HAD_IT, PARTLY, NOT_YET }

    private final PracticeAvailabilityService availability;
    private final PracticeSuppressionService suppression;
    private final PracticeSkillStateService skillState;
    private final PracticeSkillStateRepo stateRepo;
    private final PracticeCheckInAnswerRepo answerRepo;
    private final HouseholdAccessService access;
    private final Clock clock;

    @Autowired
    public PracticeCheckInService(PracticeAvailabilityService availability, PracticeSuppressionService suppression,
                                  PracticeSkillStateService skillState, PracticeSkillStateRepo stateRepo,
                                  PracticeCheckInAnswerRepo answerRepo, HouseholdAccessService access) {
        this(availability, suppression, skillState, stateRepo, answerRepo, access, Clock.systemUTC());
    }

    PracticeCheckInService(PracticeAvailabilityService availability, PracticeSuppressionService suppression,
                           PracticeSkillStateService skillState, PracticeSkillStateRepo stateRepo,
                           PracticeCheckInAnswerRepo answerRepo, HouseholdAccessService access, Clock clock) {
        this.availability = availability;
        this.suppression = suppression;
        this.skillState = skillState;
        this.stateRepo = stateRepo;
        this.answerRepo = answerRepo;
        this.access = access;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Scheduling (pure)
    // ------------------------------------------------------------------

    /** The next interval in days, from the interval {@code current} the skill was on. */
    static int nextInterval(int current, boolean correct, Confidence confidence) {
        if (!correct) return FIRST_INTERVAL;
        if (confidence == Confidence.PARTLY) return current;
        if (confidence == Confidence.NOT_YET) return Math.max(FIRST_INTERVAL, current / 2);
        for (int rung : LADDER) if (rung > current) return rung;
        return LADDER[LADDER.length - 1];
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** {@code GET /api/practice/checkins/due}. Suppressed: no cards, and why. */
    @Transactional(readOnly = true)
    public DueDto due(String callerEmail, String householdId) {
        String email = normalize(callerEmail);
        String hid = skillState.requireHousehold(email, householdId);
        SuppressionDto waiting = SuppressionDto.of(suppression.check(hid, email).orElse(null));
        if (waiting != null) return new DueDto(PracticeCheckInDtos.SCHEMA_VERSION, null, null, List.of(), waiting);
        return dueCards(hid);
    }

    /**
     * The cards due now, with no suppression check: the caller (Today) has already
     * decided the household is calm.
     */
    @Transactional(readOnly = true)
    public DueDto dueCards(String hid) {
        Instant now = clock.instant();
        Map<String, Entry> bySkill = checkInsBySkill();
        List<Standing> due = new ArrayList<>();
        for (Standing s : skillState.standings(hid)) {
            if (bySkill.containsKey(s.skillKey()) && s.dueBy(now)) due.add(s);
        }
        if (due.isEmpty()) return new DueDto(PracticeCheckInDtos.SCHEMA_VERSION, null, null, List.of(), null);
        due.sort(Comparator.comparing(Standing::dueAt).thenComparing(Standing::skillKey));

        String hazard = due.get(0).hazard();
        List<CardDto> cards = new ArrayList<>();
        for (Standing s : due) {
            if (cards.size() == MAX_CARDS) break;
            if (!hazard.equals(s.hazard())) continue;
            Version v = bySkill.get(s.skillKey()).version();
            CheckIn c = v.body().checkin();
            cards.add(new CardDto(v.key(), v.version(), s.skillKey(), c.prompt(),
                    c.choices().stream().map(ch -> new ChoiceDto(ch.key(), ch.label(), null)).toList(),
                    Boolean.TRUE.equals(c.objective())));
        }
        return new DueDto(PracticeCheckInDtos.SCHEMA_VERSION, hazard, hazardLabel(hazard), cards, null);
    }

    /** {@code GET /api/practice/skills/state}. */
    @Transactional(readOnly = true)
    public SkillStatesDto skillStates(String callerEmail, String householdId) {
        return skillState.states(normalize(callerEmail), householdId, checkInsBySkill().keySet());
    }

    /** Startable check-ins by the skill they refresh, only for skills that are themselves startable. */
    private Map<String, Entry> checkInsBySkill() {
        java.util.Set<String> skills = new java.util.HashSet<>();
        for (Entry e : availability.startable(PracticeKind.SKILL)) skills.add(e.version().key());
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Entry e : availability.startable(PracticeKind.CHECKIN)) {
            CheckIn c = e.version().body().checkin();
            if (c == null || !skills.contains(c.skill())) continue;
            out.putIfAbsent(c.skill(), e);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** {@code POST /api/practice/checkins/{key}/answer}: records the answer and schedules the skill. */
    @Transactional
    public AnswerDto answer(String callerEmail, String checkinKey, String householdId, String choiceKey) {
        String email = normalize(callerEmail);
        String hid = skillState.requireHousehold(email, householdId);
        requireCalm(hid, email);

        StartCheck check = availability.checkStart(checkinKey);
        if (!check.allowed()) {
            switch (check.denial()) {
                case DISABLED, PRACTICE_OFF -> throw new ResponseStatusException(HttpStatus.GONE, ScenarioService.WITHDRAWN);
                default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Check-in not found");
            }
        }
        Version v = check.entry().version();
        CheckIn c = v.kind() == PracticeKind.CHECKIN ? v.body().checkin() : null;
        if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Check-in not found");
        // Fail closed: a check-in for a skill that is not itself being served is not served.
        StartCheck skill = availability.checkStart(c.skill());
        if (!skill.allowed() || skill.entry().version().kind() != PracticeKind.SKILL) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Check-in not found");
        }
        if (choiceKey == null || choiceKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "choiceKey is required");
        }
        CheckInChoice chosen = c.choices().stream().filter(ch -> choiceKey.equals(ch.key())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown choice"));
        boolean correct = Boolean.TRUE.equals(chosen.correct());

        Instant now = clock.instant();
        PracticeCheckInAnswer a = new PracticeCheckInAnswer();
        a.setHouseholdId(hid);
        a.setSkillKey(c.skill());
        a.setCheckinKey(v.key());
        a.setContentVersion(v.version());
        a.setCorrect(correct);
        a.setAnsweredAt(now);
        a = answerRepo.save(a);

        PracticeSkillState existing = stateRepo.findByHouseholdIdAndSkillKey(hid, c.skill()).orElse(null);
        PracticeSkillState st = existing;
        if (st == null) {
            st = new PracticeSkillState();
            st.setHouseholdId(hid);
            st.setSkillKey(c.skill());
            st.setIntervalDays(FIRST_INTERVAL);
        }
        // A correct answer that arrives before the skill is due is recorded but does not climb the
        // ladder; a miss always counts (it resets to the first interval).
        boolean early = correct && existing != null && existing.getNextDueAt() != null && existing.getNextDueAt().isAfter(now);
        if (!early) {
            int prior = st.getIntervalDays();
            int next = nextInterval(prior, correct, null);
            st.setPriorIntervalDays(prior);
            st.setIntervalDays(next);
            st.setNextDueAt(now.plus(next, ChronoUnit.DAYS));
            st.setLastResult(correct ? "CORRECT" : "INCORRECT");
            st.setLastConfidence(null);
        }
        st.setLastSeenAt(now);
        st.setUpdatedAt(now);
        try {
            stateRepo.saveAndFlush(st);
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            // Two first answers raced on the unique row; the loser rolls back and retries.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Please try again");
        }

        return new AnswerDto(a.getId(), c.remember(), correct ? null : c.fullerExplanation());
    }

    /**
     * {@code POST /api/practice/checkins/answers/{id}/confidence}: re-schedules from that
     * answer, but only while it is still the skill's newest. A stale tap (the household
     * answered again since) changes nothing.
     */
    @Transactional
    public void confidence(String callerEmail, long answerId, String confidenceName) {
        String email = normalize(callerEmail);
        Confidence confidence;
        try {
            confidence = Confidence.valueOf(confidenceName == null ? "" : confidenceName.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confidence must be HAD_IT, PARTLY or NOT_YET");
        }
        PracticeCheckInAnswer a = answerRepo.findById(answerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Answer not found"));
        access.requireCanReadHousehold(email, a.getHouseholdId());
        requireCalm(a.getHouseholdId(), email);

        Optional<PracticeCheckInAnswer> newest = answerRepo
                .findFirstByHouseholdIdAndSkillKeyOrderByAnsweredAtDescIdDesc(a.getHouseholdId(), a.getSkillKey());
        if (newest.isEmpty() || !newest.get().getId().equals(a.getId())) return;
        PracticeSkillState st = stateRepo.findByHouseholdIdAndSkillKey(a.getHouseholdId(), a.getSkillKey()).orElse(null);
        if (st == null) return;

        int next = nextInterval(st.getPriorIntervalDays(), a.isCorrect(), confidence);
        a.setConfidence(confidence.name());
        answerRepo.save(a);
        st.setIntervalDays(next);
        st.setNextDueAt(a.getAnsweredAt().plus(next, ChronoUnit.DAYS));
        st.setLastConfidence(confidence.name());
        st.setUpdatedAt(clock.instant());
        stateRepo.save(st);
    }

    // ------------------------------------------------------------------

    private void requireCalm(String hid, String email) {
        if (suppression.check(hid, email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ScenarioService.WAITING);
        }
    }

    /** "flash-flood" → "Flash flood". Presentation only; the key is the identity. */
    static String hazardLabel(String hazard) {
        if (hazard == null || hazard.isBlank()) return null;
        String spaced = hazard.replace('-', ' ');
        return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
