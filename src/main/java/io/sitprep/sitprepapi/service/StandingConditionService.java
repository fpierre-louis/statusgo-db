package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.HouseholdStandingCondition;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.StandingConditionDtos.*;
import io.sitprep.sitprepapi.repo.HouseholdStandingConditionRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Household Standing Conditions — create, update, clear, read.
 *
 * <p>Deliberately small. There is no acknowledge, no assign, no snooze, no
 * escalate and no complete: those are workflow states belonging to other
 * systems, and every one of them would invite this to become a task tracker.
 * A condition is true until a person says it is not.
 */
@Service
public class StandingConditionService {

    public static final String ACTIVE = "ACTIVE";
    public static final String CLEARED = "CLEARED";

    /**
     * The whole taxonomy. Small on purpose — it organises and picks an icon,
     * and it is NOT a hazard model. Encoding every disaster type here would
     * make the category look like it carries meaning it does not.
     */
    public static final Set<String> CATEGORIES = Set.of(
            "WATER", "POWER", "TRANSPORTATION", "ACCESS", "COMMUNICATION", "SUPPLIES", "OTHER");

    private static final int TITLE_MAX = 120;
    private static final int INSTRUCTION_MAX = 500;

    private final HouseholdStandingConditionRepo repo;
    private final HouseholdAccessService access;
    private final UserInfoRepo userInfoRepo;

    public StandingConditionService(HouseholdStandingConditionRepo repo,
                                    HouseholdAccessService access,
                                    UserInfoRepo userInfoRepo) {
        this.repo = repo;
        this.access = access;
        this.userInfoRepo = userInfoRepo;
    }

    /** Active conditions for a household. Any household member may read. */
    @Transactional(readOnly = true)
    public StandingConditionsDoc activeFor(String householdId, String caller) {
        access.requireCanReadHousehold(caller, householdId);
        return new StandingConditionsDoc(
                repo.findByHouseholdIdAndStatusOrderByUpdatedAtDesc(householdId, ACTIVE)
                        .stream().map(this::toDto).toList(),
                Instant.now());
    }

    /**
     * Projection for the household plan document.
     *
     * <p>Riding the plan doc is what gives cached reading and printing for free
     * — it is the payload `meCache` already mirrors, so a household that opens
     * the app with no connection still sees what is affecting them. Authorized
     * by the caller of the plan document, not here.
     */
    @Transactional(readOnly = true)
    public List<StandingConditionDto> activeForProjection(String householdId) {
        if (householdId == null || householdId.isBlank()) return List.of();
        return repo.findByHouseholdIdAndStatusOrderByUpdatedAtDesc(householdId, ACTIVE)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public StandingConditionDto create(String householdId, String caller, StandingConditionRequest req) {
        access.requireCanAdminHousehold(caller, householdId);
        Instant now = Instant.now();

        HouseholdStandingCondition c = new HouseholdStandingCondition();
        c.setHouseholdId(householdId);
        c.setCategory(validCategory(req.category()));
        c.setTitle(requiredTitle(req.title()));
        c.setInstruction(trimTo(req.instruction(), INSTRUCTION_MAX));
        c.setStatus(ACTIVE);
        c.setCreatedAt(now);
        c.setCreatedByEmail(caller);
        c.setUpdatedAt(now);
        c.setUpdatedByEmail(caller);
        return toDto(repo.save(c));
    }

    @Transactional
    public StandingConditionDto update(String householdId, Long id, String caller,
                                       StandingConditionRequest req) {
        access.requireCanAdminHousehold(caller, householdId);
        HouseholdStandingCondition c = load(householdId, id);
        c.setCategory(validCategory(req.category()));
        c.setTitle(requiredTitle(req.title()));
        c.setInstruction(trimTo(req.instruction(), INSTRUCTION_MAX));
        // Editing a cleared condition brings it back — a household that clears
        // one by mistake, or whose situation returns, should not have to retype
        // it. Explicit and reversible beats a second row saying the same thing.
        c.setStatus(ACTIVE);
        c.setClearedAt(null);
        c.setClearedByEmail(null);
        c.setUpdatedAt(Instant.now());
        c.setUpdatedByEmail(caller);
        return toDto(repo.save(c));
    }

    /**
     * Clear it.
     *
     * <p>A lifecycle transition, NOT a delete. "Who decided this was over, and
     * when" is exactly the question somebody asks afterwards, and deleting the
     * row destroys the only record of it. It also means an alert expiring can
     * never be mistaken for a household decision, because a household decision
     * always has a name on it.
     */
    @Transactional
    public StandingConditionDto clear(String householdId, Long id, String caller) {
        access.requireCanAdminHousehold(caller, householdId);
        HouseholdStandingCondition c = load(householdId, id);
        if (c.isActive()) {
            Instant now = Instant.now();
            c.setStatus(CLEARED);
            c.setClearedAt(now);
            c.setClearedByEmail(caller);
            c.setUpdatedAt(now);
            c.setUpdatedByEmail(caller);
            c = repo.save(c);
        }
        return toDto(c);
    }

    private HouseholdStandingCondition load(String householdId, Long id) {
        HouseholdStandingCondition c = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such condition"));
        // Never let an id from one household address a row in another.
        if (!householdId.equals(c.getHouseholdId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such condition");
        }
        return c;
    }

    private String validCategory(String raw) {
        String key = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "category must be one of " + CATEGORIES);
        }
        return key;
    }

    private String requiredTitle(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        return t.length() > TITLE_MAX ? t.substring(0, TITLE_MAX) : t;
    }

    private String trimTo(String raw, int max) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private StandingConditionDto toDto(HouseholdStandingCondition c) {
        return new StandingConditionDto(
                c.getId(), c.getCategory(), c.getTitle(), c.getInstruction(), c.getStatus(),
                c.getCreatedAt(), c.getUpdatedAt(), c.getUpdatedByEmail(),
                displayName(c.getUpdatedByEmail()),
                c.getClearedAt(), c.getClearedByEmail());
    }

    /** Resolved server-side so the client never looks a person up to render a byline. */
    private String displayName(String email) {
        if (email == null || email.isBlank()) return null;
        return userInfoRepo.findByUserEmailIgnoreCase(email)
                .map(UserInfo::getUserFirstName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);
    }
}
