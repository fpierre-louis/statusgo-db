package io.sitprep.sitprepapi.routine;

import com.fasterxml.jackson.databind.JsonNode;
import io.sitprep.sitprepapi.constant.HouseholdBand;
import io.sitprep.sitprepapi.domain.EmergencyContact;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdManualMember;
import io.sitprep.sitprepapi.domain.HouseholdMemberBand;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.HouseholdRoutineDto;
import io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.OptionDto;
import io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.RoutineOfferDto;
import io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.SlotOfferDto;
import io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.SlotValueDto;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService;
import io.sitprep.sitprepapi.practice.PracticeCatalog;
import io.sitprep.sitprepapi.practice.PracticeContent.Routine;
import io.sitprep.sitprepapi.practice.PracticeContent.RoutineSlot;
import io.sitprep.sitprepapi.practice.PracticeContent.RoutineSlotSource;
import io.sitprep.sitprepapi.practice.PracticeContentValidator;
import io.sitprep.sitprepapi.practice.PracticeRoutines;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdManualMemberRepo;
import io.sitprep.sitprepapi.repo.HouseholdMemberBandRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.ActivationPlanUpdateBroadcastService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Household routines (EXEC-R1, "Save to plan"). Lives outside {@code practice/} on purpose: a saved
 * routine is plan data, written only by an explicit save from a household owner or admin, never by
 * a Practice run, and untouched by "Clear practice history". Practice only supplies the reviewed
 * template, read through {@link PracticeRoutines} and the same availability gate as every kind.
 *
 * <p>Nothing here accepts or stores a Together role, a turn, a child, or any Practice data: the
 * request body is parsed field by field and every unknown field is a 400.</p>
 */
@Service
public class HouseholdRoutineService {

    private static final String HOUSEHOLD = "Household";
    private static final int REF_MAX = 120;

    private final HouseholdRoutineRepo repo;
    private final HouseholdAccessService access;
    private final PracticeAvailabilityService availability;
    private final PracticeCatalog catalog;
    private final GroupRepo groupRepo;
    private final MeetingPlaceRepo meetingPlaceRepo;
    private final EmergencyContactGroupRepo contactGroupRepo;
    private final HouseholdManualMemberRepo manualRepo;
    private final HouseholdMemberBandRepo bandRepo;
    private final UserInfoRepo userInfoRepo;
    private final Clock clock;

    // Setter-injected like the other plan writers, so hand-built tests stay unchanged.
    private ActivationPlanUpdateBroadcastService planBroadcast;

    @Autowired
    public HouseholdRoutineService(HouseholdRoutineRepo repo, HouseholdAccessService access,
                                   PracticeAvailabilityService availability, PracticeCatalog catalog,
                                   GroupRepo groupRepo, MeetingPlaceRepo meetingPlaceRepo,
                                   EmergencyContactGroupRepo contactGroupRepo,
                                   HouseholdManualMemberRepo manualRepo, HouseholdMemberBandRepo bandRepo,
                                   UserInfoRepo userInfoRepo) {
        this(repo, access, availability, catalog, groupRepo, meetingPlaceRepo, contactGroupRepo, manualRepo,
                bandRepo, userInfoRepo, Clock.systemUTC());
    }

    HouseholdRoutineService(HouseholdRoutineRepo repo, HouseholdAccessService access,
                            PracticeAvailabilityService availability, PracticeCatalog catalog,
                            GroupRepo groupRepo, MeetingPlaceRepo meetingPlaceRepo,
                            EmergencyContactGroupRepo contactGroupRepo,
                            HouseholdManualMemberRepo manualRepo, HouseholdMemberBandRepo bandRepo,
                            UserInfoRepo userInfoRepo, Clock clock) {
        this.repo = repo;
        this.access = access;
        this.availability = availability;
        this.catalog = catalog;
        this.groupRepo = groupRepo;
        this.meetingPlaceRepo = meetingPlaceRepo;
        this.contactGroupRepo = contactGroupRepo;
        this.manualRepo = manualRepo;
        this.bandRepo = bandRepo;
        this.userInfoRepo = userInfoRepo;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setPlanBroadcast(ActivationPlanUpdateBroadcastService planBroadcast) {
        this.planBroadcast = planBroadcast;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** The builder's data: the reviewed template, the plan-derived options, the saved row, and whether the viewer may save. */
    @Transactional(readOnly = true)
    public RoutineOfferDto offer(String householdId, String routineKey, String callerEmail) {
        access.requireCanReadHousehold(callerEmail, householdId);
        Group household = requireHousehold(householdId);
        PracticeAvailabilityService.Entry entry = servable(routineKey);
        Routine r = entry.version().body().routine();
        Map<String, List<OptionDto>> options = resolveOptions(household, r);
        List<SlotOfferDto> slots = new ArrayList<>();
        for (RoutineSlot s : r.slots()) {
            slots.add(new SlotOfferDto(s.key(), s.source().name(), options.getOrDefault(s.key(), List.of()),
                    s.fallback(), s.source() != RoutineSlotSource.CHOICE));
        }
        HouseholdRoutineDto saved = repo.findByHouseholdIdAndRoutineKey(householdId, routineKey)
                .map(this::toDto).orElse(null);
        return new RoutineOfferDto(entry.version().key(), entry.version().version(), r.hazard(), r.title(),
                r.ifText(), r.thenTemplate(), slots, saved, access.canWriteHousehold(callerEmail, householdId),
                entry.preview());
    }

    /** The household's saved routines for the plan document: hazard, then title. No access check (the plan resource gates). */
    @Transactional(readOnly = true)
    public List<HouseholdRoutineDto> listForPlan(String householdId) {
        List<HouseholdRoutineDto> out = new ArrayList<>();
        for (HouseholdRoutine row : repo.findByHouseholdId(householdId)) out.add(toDto(row));
        out.sort(Comparator.comparing((HouseholdRoutineDto d) -> d.hazard() == null ? "" : d.hazard())
                .thenComparing(d -> d.title() == null ? "" : d.title().toLowerCase(Locale.ROOT))
                .thenComparing(HouseholdRoutineDto::routineKey));
        return out;
    }

    // ------------------------------------------------------------------
    // Writes (household owner / admin only)
    // ------------------------------------------------------------------

    /**
     * Upsert the household's routine. {@code body} is the raw JSON tree so unknown fields can be refused
     * at both levels ({@code {contentVersion, slots:{key:{value, refId?}}}}).
     */
    @Transactional
    public HouseholdRoutineDto save(String householdId, String routineKey, String callerEmail, JsonNode body) {
        access.requireCanAdminHousehold(callerEmail, householdId);
        Group household = requireHousehold(householdId);
        PracticeAvailabilityService.Entry entry = servable(routineKey);
        Routine r = entry.version().body().routine();

        ParsedBody parsed = parse(body);
        if (parsed.contentVersion() != entry.version().version()) {
            throw new OptimisticLockingFailureException("This routine changed; reload before saving it");
        }
        Map<String, List<OptionDto>> options = resolveOptions(household, r);
        Map<String, RoutineSlot> declared = new LinkedHashMap<>();
        for (RoutineSlot s : r.slots()) declared.put(s.key(), s);

        Map<String, HouseholdRoutine.SlotValue> chosen = new LinkedHashMap<>();
        for (Map.Entry<String, RawSlot> e : parsed.slots().entrySet()) {
            RoutineSlot slot = declared.get(e.getKey());
            if (slot == null) throw new RoutineInvalidException("This routine has no slot '" + shown(e.getKey()) + "'");
            chosen.put(slot.key(), validateSlot(slot, e.getValue(), options.getOrDefault(slot.key(), List.of())));
        }

        String thenText = PracticeRoutines.render(r, k -> chosen.containsKey(k) ? chosen.get(k).value() : null);
        if (thenText.length() > PracticeContentValidator.ROUTINE_SAVED_THEN_MAX) {
            throw new RoutineInvalidException("That line is too long to save; shorten what you typed");
        }
        String actor = lower(callerEmail);
        Instant now = clock.instant();
        HouseholdRoutine row = repo.findByHouseholdIdAndRoutineKey(householdId, routineKey).orElse(null);
        if (row == null) {
            row = new HouseholdRoutine();
            row.setHouseholdId(householdId);
            row.setRoutineKey(routineKey);
            row.setCreatedAt(now);
            row.setCreatedByEmail(actor);
        }
        row.setContentVersion(entry.version().version());
        row.setHazardKey(r.hazard());
        row.setIfText(r.ifText());
        row.setThenText(thenText);
        row.setSlotValues(orderedBySlot(r, chosen));
        row.setUpdatedAt(now);
        row.setUpdatedByEmail(actor);
        HouseholdRoutine saved;
        try {
            saved = repo.saveAndFlush(row);
        } catch (DataIntegrityViolationException race) {
            // Two admins saved the same routine for the first time at once: the loser retries as an update.
            throw new OptimisticLockingFailureException("Routine was saved by someone else at the same time", race);
        }
        announce(householdId);
        return toDto(saved);
    }

    /** Remove a routine from the plan. Idempotent: removing one that is not there is still a 204. */
    @Transactional
    public void delete(String householdId, String routineKey, String callerEmail) {
        access.requireCanAdminHousehold(callerEmail, householdId);
        requireHousehold(householdId);
        if (repo.deleteByHouseholdIdAndRoutineKey(householdId, routineKey) > 0) announce(householdId);
    }

    // ------------------------------------------------------------------
    // Body parsing (strict)
    // ------------------------------------------------------------------

    record RawSlot(String value, String refId) {}

    record ParsedBody(int contentVersion, Map<String, RawSlot> slots) {}

    static ParsedBody parse(JsonNode body) {
        if (body == null || !body.isObject()) throw new RoutineInvalidException("Send {contentVersion, slots}");
        for (String name : (Iterable<String>) body::fieldNames) {
            if (!name.equals("contentVersion") && !name.equals("slots")) {
                throw new RoutineInvalidException("Unexpected field '" + shown(name) + "'");
            }
        }
        JsonNode v = body.get("contentVersion");
        if (v == null || !v.isInt() || v.intValue() < 1) throw new RoutineInvalidException("contentVersion must be a whole number");
        Map<String, RawSlot> slots = new LinkedHashMap<>();
        JsonNode s = body.get("slots");
        if (s != null && !s.isNull()) {
            if (!s.isObject()) throw new RoutineInvalidException("slots must be an object");
            for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) s::fields) {
                JsonNode slot = e.getValue();
                if (slot == null || !slot.isObject()) {
                    throw new RoutineInvalidException("Slot '" + shown(e.getKey()) + "' must be {value, refId?}");
                }
                for (String name : (Iterable<String>) slot::fieldNames) {
                    if (!name.equals("value") && !name.equals("refId")) {
                        throw new RoutineInvalidException("Unexpected field '" + shown(name) + "' in slot '" + shown(e.getKey()) + "'");
                    }
                }
                JsonNode value = slot.get("value");
                if (value == null || !value.isTextual()) {
                    throw new RoutineInvalidException("Slot '" + shown(e.getKey()) + "' needs a text value");
                }
                JsonNode ref = slot.get("refId");
                if (ref != null && !ref.isNull() && !ref.isTextual()) {
                    throw new RoutineInvalidException("Slot '" + shown(e.getKey()) + "' refId must be text");
                }
                slots.put(e.getKey(), new RawSlot(value.textValue(), ref == null || ref.isNull() ? null : ref.textValue()));
            }
        }
        return new ParsedBody(v.intValue(), slots);
    }

    private static HouseholdRoutine.SlotValue validateSlot(RoutineSlot slot, RawSlot raw, List<OptionDto> options) {
        String at = "Slot '" + slot.key() + "'";
        String value = raw.value() == null ? "" : raw.value().strip();
        if (value.isEmpty() || value.length() > PracticeContentValidator.ROUTINE_VALUE_MAX) {
            throw new RoutineInvalidException(at + " must be 1-" + PracticeContentValidator.ROUTINE_VALUE_MAX + " characters");
        }
        if (value.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029)) {
            throw new RoutineInvalidException(at + " has a control character");
        }
        String ref = raw.refId() == null || raw.refId().isBlank() ? null : raw.refId().strip();
        if (ref != null && ref.length() > REF_MAX) throw new RoutineInvalidException(at + " has an invalid refId");
        switch (slot.source()) {
            case CHOICE -> {
                if (ref != null) throw new RoutineInvalidException(at + " takes no refId");
                if (options.stream().noneMatch(o -> o.value().equals(value))) {
                    throw new RoutineInvalidException(at + " must be one of its options");
                }
            }
            case TEXT -> {
                if (ref != null) throw new RoutineInvalidException(at + " takes no refId");
            }
            default -> {
                if (ref != null) {
                    Optional<OptionDto> match = options.stream().filter(o -> ref.equals(o.refId())).findFirst();
                    if (match.isEmpty()) throw new RoutineInvalidException(at + " refers to something that is not in this household's plan");
                    if (!match.get().value().equals(value)) throw new RoutineInvalidException(at + " does not match what its refId names");
                }
            }
        }
        return new HouseholdRoutine.SlotValue(value, ref);
    }

    // ------------------------------------------------------------------
    // Options resolved from the household plan
    // ------------------------------------------------------------------

    private Map<String, List<OptionDto>> resolveOptions(Group household, Routine r) {
        Map<String, List<OptionDto>> out = new HashMap<>();
        List<OptionDto> adults = null;
        List<MeetingPlace> places = null;
        List<EmergencyContact> contacts = null;
        for (RoutineSlot s : r.slots()) {
            switch (s.source()) {
                case ADULT -> {
                    if (adults == null) adults = adultOptions(household);
                    List<OptionDto> withFallback = new ArrayList<>(adults);
                    withFallback.add(new OptionDto(s.fallback(), null));
                    out.put(s.key(), withFallback);
                }
                case PLACE -> {
                    if (places == null) places = meetingPlaceRepo.findByHouseholdId(household.getGroupId());
                    out.put(s.key(), placeOptions(places, s.tier()));
                }
                case CONTACT -> {
                    if (contacts == null) contacts = contactsOf(household.getGroupId());
                    out.put(s.key(), contactOptions(contacts, s.contactType()));
                }
                case CHOICE -> {
                    List<OptionDto> o = new ArrayList<>();
                    for (String opt : s.options()) o.add(new OptionDto(opt, null));
                    out.put(s.key(), o);
                }
                case TEXT -> out.put(s.key(), List.of());
            }
        }
        return out;
    }

    /** Adults only: account members banded ADULT (a claimed teen account is not), and manual members who are adults. Never children, never pets. */
    private List<OptionDto> adultOptions(Group household) {
        String hid = household.getGroupId();
        Map<String, HouseholdBand> bands = new HashMap<>();
        for (HouseholdMemberBand b : bandRepo.findByHouseholdId(hid)) {
            if (b.getUserEmail() != null && b.getBand() != null) bands.put(lower(b.getUserEmail()), b.getBand());
        }
        Set<String> emails = new LinkedHashSet<>();
        if (household.getMemberEmails() != null) {
            for (String e : household.getMemberEmails()) if (lower(e) != null) emails.add(lower(e));
        }
        Map<String, UserInfo> profiles = new HashMap<>();
        if (!emails.isEmpty()) {
            List<UserInfo> found = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails));
            if (found != null) for (UserInfo u : found) if (u != null && lower(u.getUserEmail()) != null) profiles.put(lower(u.getUserEmail()), u);
        }
        List<OptionDto> out = new ArrayList<>();
        for (String email : emails) {
            if (bands.getOrDefault(email, HouseholdBand.ADULT) != HouseholdBand.ADULT) continue;
            UserInfo u = profiles.get(email);
            String first = u == null ? null : firstWord(u.getUserFirstName());
            if (first == null || u.getId() == null) continue;
            out.add(new OptionDto(cap(first), "user:" + u.getId()));
        }
        for (HouseholdManualMember m : manualRepo.findByHouseholdIdOrderByCreatedAtAsc(hid)) {
            if (!Boolean.TRUE.equals(m.getIsAdult()) || m.effectiveBand() != HouseholdBand.ADULT) continue;
            String first = firstWord(m.getName());
            if (first == null) continue;
            out.add(new OptionDto(cap(first), "manual:" + m.getId()));
        }
        return out;
    }

    private static List<OptionDto> placeOptions(List<MeetingPlace> places, String tier) {
        List<MeetingPlace> match = new ArrayList<>();
        for (MeetingPlace p : places) {
            if (p.getMeetingTier() != null && p.getMeetingTier().name().equals(tier) && clean(p.getName()) != null) match.add(p);
        }
        // The plan's primary pick first, then oldest first.
        match.sort(Comparator.comparing((MeetingPlace p) -> !p.isDeploy())
                .thenComparing(p -> p.getId() == null ? Long.MAX_VALUE : p.getId()));
        List<OptionDto> out = new ArrayList<>();
        for (MeetingPlace p : match) out.add(new OptionDto(cap(clean(p.getName())), "place:" + p.getId()));
        return out;
    }

    private List<EmergencyContact> contactsOf(String householdId) {
        List<EmergencyContact> all = new ArrayList<>();
        for (EmergencyContactGroup g : contactGroupRepo.findByHouseholdId(householdId)) {
            if (g.getContacts() != null) all.addAll(g.getContacts());
        }
        return all;
    }

    private static List<OptionDto> contactOptions(List<EmergencyContact> contacts, String contactType) {
        List<EmergencyContact> match = new ArrayList<>();
        for (EmergencyContact c : contacts) {
            boolean typeOk = "ANY".equals(contactType)
                    || (c.getContactType() != null && c.getContactType().name().equals(contactType));
            if (typeOk && clean(c.getName()) != null) match.add(c);
        }
        match.sort(Comparator.comparing((EmergencyContact c) -> c.getId() == null ? Long.MAX_VALUE : c.getId()));
        List<OptionDto> out = new ArrayList<>();
        for (EmergencyContact c : match) out.add(new OptionDto(cap(clean(c.getName())), "contact:" + c.getId()));
        return out;
    }

    // ------------------------------------------------------------------
    // Shaping
    // ------------------------------------------------------------------

    private static Map<String, HouseholdRoutine.SlotValue> orderedBySlot(Routine r, Map<String, HouseholdRoutine.SlotValue> chosen) {
        Map<String, HouseholdRoutine.SlotValue> out = new LinkedHashMap<>();
        for (RoutineSlot s : r.slots()) if (chosen.containsKey(s.key())) out.put(s.key(), chosen.get(s.key()));
        return out;
    }

    HouseholdRoutineDto toDto(HouseholdRoutine row) {
        Optional<Routine> content = catalog.exact(row.getRoutineKey(), row.getContentVersion())
                .map(v -> v.body().routine());
        String title = content.map(Routine::title).orElse(row.getIfText());
        List<String> order = new ArrayList<>();
        content.ifPresent(c -> c.slots().forEach(s -> order.add(s.key())));
        List<String> keys = new ArrayList<>(row.getSlotValues() == null ? Set.<String>of() : row.getSlotValues().keySet());
        keys.sort(Comparator.comparingInt((String k) -> order.contains(k) ? order.indexOf(k) : Integer.MAX_VALUE)
                .thenComparing(k -> k));
        List<SlotValueDto> slots = new ArrayList<>();
        for (String k : keys) {
            HouseholdRoutine.SlotValue sv = row.getSlotValues().get(k);
            if (sv != null) slots.add(new SlotValueDto(k, sv.value(), sv.refId()));
        }
        return new HouseholdRoutineDto(row.getRoutineKey(), row.getHazardKey(), title, row.getIfText(),
                row.getThenText(), slots, row.getContentVersion(), row.getUpdatedAt());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Group requireHousehold(String householdId) {
        return groupRepo.findByGroupId(householdId == null ? "" : householdId)
                .filter(g -> HOUSEHOLD.equalsIgnoreCase(g.getGroupType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found"));
    }

    private PracticeAvailabilityService.Entry servable(String routineKey) {
        return PracticeRoutines.servable(availability, routineKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Routine not found"));
    }

    private void announce(String householdId) {
        if (planBroadcast != null) planBroadcast.broadcastHouseholdPlanChangedAfterCommit(householdId, "routines");
    }

    private static String lower(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private static String firstWord(String name) {
        String t = clean(name);
        if (t == null) return null;
        int sp = t.indexOf(' ');
        return sp > 0 ? t.substring(0, sp) : t;
    }

    /** A plan name longer than a saved value allows is cut on a code point, so the option can be saved as shown. */
    private static String cap(String s) {
        int max = PracticeContentValidator.ROUTINE_VALUE_MAX;
        if (s.length() <= max) return s;
        int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, end).strip();
    }

    private static String shown(String s) {
        if (s == null) return "";
        String t = s.length() > 40 ? s.substring(0, 40) : s;
        return t.replaceAll("\\p{Cntrl}", "?");
    }
}
