package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EmergencyContact;
import io.sitprep.sitprepapi.domain.EmergencySupportAssignment;
import io.sitprep.sitprepapi.domain.EmergencySupportProfile;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.EmergencySupportDtos.*;
import io.sitprep.sitprepapi.repo.EmergencyContactRepo;
import io.sitprep.sitprepapi.repo.EmergencySupportAssignmentRepo;
import io.sitprep.sitprepapi.repo.EmergencySupportProfileRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RC-3 Phase 2 — Emergency Need Profiles (Level 1) and prepared Support Plans
 * (Level 2).
 *
 * <p><b>Authorization, stated once.</b> Reads require household membership;
 * writes require household admin OR the subject editing their own profile.
 * Group admins get nothing — a church or business admin must not receive a
 * member's support needs because that person joined their group — and there is
 * no anonymous path to any of this. Both gates delegate to
 * {@link HouseholdAccessService}, the existing owner of household authority,
 * rather than re-deriving membership here.</p>
 *
 * <p><b>What this service will not do.</b> No acceptance, no availability, no
 * acknowledgement. An assignment is what the household PREPARED; whether the
 * helper knows or agrees is Level 3, and shipping assignments does not make it
 * exist.</p>
 */
@Service
public class EmergencySupportService {

    private static final int MAX_NOTE = 240;
    private static final int MAX_SHORT_NOTE = 160;
    private static final int MAX_TINY_NOTE = 120;

    private static final Set<String> SUBJECT_TYPES = Set.of("user", "manual");
    private static final Set<String> COMMUNICATION_NEEDS =
            Arrays.stream(CommunicationNeed.values()).map(Enum::name).collect(Collectors.toSet());

    private final EmergencySupportProfileRepo profileRepo;
    private final EmergencySupportAssignmentRepo assignmentRepo;
    private final EmergencyContactRepo contactRepo;
    private final UserInfoRepo userInfoRepo;
    private final HouseholdAccessService access;

    public EmergencySupportService(EmergencySupportProfileRepo profileRepo,
                                   EmergencySupportAssignmentRepo assignmentRepo,
                                   EmergencyContactRepo contactRepo,
                                   UserInfoRepo userInfoRepo,
                                   HouseholdAccessService access) {
        this.profileRepo = profileRepo;
        this.assignmentRepo = assignmentRepo;
        this.contactRepo = contactRepo;
        this.userInfoRepo = userInfoRepo;
        this.access = access;
    }

    // ── Reads ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SupportProfileDto> listProfiles(String householdId, String caller) {
        access.requireCanReadHousehold(caller, householdId);
        return profileRepo.findByHouseholdId(householdId).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<SupportAssignmentDto> listAssignments(String householdId, String caller) {
        access.requireCanReadHousehold(caller, householdId);
        return assignmentRepo.findByHouseholdId(householdId).stream().map(this::toDto).toList();
    }

    /**
     * Profiles for the plan document, WITHOUT a caller check.
     *
     * <p>Safe because the only caller is the household plan endpoint, which is
     * already gated to household members — the same audience the visibility
     * matrix grants read. Package-visible callers must not widen that.</p>
     */
    @Transactional(readOnly = true)
    public List<SupportProfileDto> listProfilesForPlan(String householdId) {
        if (householdId == null) return List.of();
        return profileRepo.findByHouseholdId(householdId).stream().map(this::toDto).toList();
    }

    /** Assignments for the plan document. Same gate rationale as above. */
    @Transactional(readOnly = true)
    public List<SupportAssignmentDto> listAssignmentsForPlan(String householdId) {
        if (householdId == null) return List.of();
        return assignmentRepo.findByHouseholdId(householdId).stream().map(this::toDto).toList();
    }

    /**
     * Which subjects in this household have a profile at all.
     *
     * <p>The roster's question, and the reason there is no denormalized flag
     * column: one query answers it for the whole household without loading a
     * single sensitive field.</p>
     *
     * @return keys of the form {@code "user:alice@x.com"} / {@code "manual:abc"}
     */
    @Transactional(readOnly = true)
    public Set<String> subjectsNeedingSupport(String householdId) {
        if (householdId == null) return Set.of();
        return profileRepo.findByHouseholdId(householdId).stream()
                .map(p -> subjectKey(p.getSubjectType(), p.getSubjectId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static String subjectKey(String subjectType, String subjectId) {
        return normalizeType(subjectType) + ":" + normalizeId(subjectType, subjectId);
    }

    // ── Writes ───────────────────────────────────────────────────────────

    /**
     * Whole-profile upsert.
     *
     * <p>Deliberately not a field patch: the form is small, and partial updates
     * over a record of booleans invite the "omitted means false" defect that
     * {@code UserSavedLocation.isHome} already cost this codebase once.</p>
     */
    @Transactional
    public SupportProfileDto upsertProfile(String householdId, String subjectType, String subjectId,
                                           SupportProfileRequest req, String caller) {
        requireCanEditSubject(householdId, subjectType, subjectId, caller);
        String type = requireSubjectType(subjectType);
        String id = normalizeId(type, subjectId);

        EmergencySupportProfile p = profileRepo
                .findByHouseholdIdAndSubjectTypeAndSubjectId(householdId, type, id)
                .orElseGet(EmergencySupportProfile::new);
        p.setHouseholdId(householdId);
        p.setSubjectType(type);
        p.setSubjectId(id);

        p.setNeedsEvacuationAssistance(req.needsEvacuationAssistance());
        p.setCannotUseStairs(req.cannotUseStairs());
        p.setMobilityNote(clamp(req.mobilityNote(), MAX_TINY_NOTE));
        p.setCommunicationNeeds(sanitizeNeeds(req.communicationNeeds()));
        p.setPreferredLanguage(clamp(req.preferredLanguage(), 40));
        p.setPowerDependentEquipment(req.powerDependentEquipment());
        p.setEquipmentNote(clamp(req.equipmentNote(), MAX_TINY_NOTE));
        p.setRefrigeratedMedication(req.refrigeratedMedication());
        p.setCriticalMedication(req.criticalMedication());
        p.setAccessibleTransportNeeded(req.accessibleTransportNeeded());
        p.setServiceAnimal(req.serviceAnimal());
        p.setServiceAnimalNote(clamp(req.serviceAnimalNote(), MAX_TINY_NOTE));
        p.setSupportNote(clamp(req.supportNote(), MAX_NOTE));
        p.setUpdatedByEmail(normalizeEmail(caller));

        return toDto(profileRepo.save(p));
    }

    /**
     * Removing the profile removes the assignments with it.
     *
     * <p>"This person no longer needs support" and "these people are prepared to
     * help them" cannot be true separately. Leaving orphaned assignments behind
     * would keep sensitive planning data alive behind a screen that no longer
     * renders it.</p>
     */
    @Transactional
    public void deleteProfile(String householdId, String subjectType, String subjectId, String caller) {
        requireCanEditSubject(householdId, subjectType, subjectId, caller);
        String type = requireSubjectType(subjectType);
        String id = normalizeId(type, subjectId);
        profileRepo.deleteByHouseholdIdAndSubjectTypeAndSubjectId(householdId, type, id);
        assignmentRepo.deleteByHouseholdIdAndSubjectTypeAndSubjectId(householdId, type, id);
    }

    @Transactional
    public SupportAssignmentDto upsertAssignment(String householdId, String subjectType, String subjectId,
                                                 String role, SupportAssignmentRequest req, String caller) {
        requireCanEditSubject(householdId, subjectType, subjectId, caller);
        String type = requireSubjectType(subjectType);
        String id = normalizeId(type, subjectId);
        EmergencySupportAssignment.Role r = parseRole(role);
        EmergencySupportAssignment.HelperType helperType = parseHelperType(req.helperType());

        EmergencySupportAssignment a = assignmentRepo
                .findByHouseholdIdAndSubjectTypeAndSubjectIdAndRole(householdId, type, id, r)
                .orElseGet(EmergencySupportAssignment::new);
        a.setHouseholdId(householdId);
        a.setSubjectType(type);
        a.setSubjectId(id);
        a.setRole(r);
        a.setHelperType(helperType);
        a.setHelperNote(clamp(req.helperNote(), MAX_SHORT_NOTE));
        a.setAssignedByEmail(normalizeEmail(caller));

        if (helperType == EmergencySupportAssignment.HelperType.MEMBER) {
            String email = normalizeEmail(req.helperUserEmail());
            if (email == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A member helper needs helperUserEmail");
            }
            a.setHelperUserEmail(email);
            a.setHelperContactId(null);
            a.setHelperName(resolveMemberName(email));
        } else {
            Long contactId = req.helperContactId();
            if (contactId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A contact helper needs helperContactId");
            }
            EmergencyContact contact = contactRepo.findById(contactId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "No such emergency contact"));
            a.setHelperContactId(contactId);
            a.setHelperUserEmail(null);
            a.setHelperName(clamp(contact.getName(), 160));
        }

        return toDto(assignmentRepo.save(a));
    }

    @Transactional
    public void deleteAssignment(String householdId, String subjectType, String subjectId,
                                 String role, String caller) {
        requireCanEditSubject(householdId, subjectType, subjectId, caller);
        String type = requireSubjectType(subjectType);
        String id = normalizeId(type, subjectId);
        assignmentRepo
                .findByHouseholdIdAndSubjectTypeAndSubjectIdAndRole(householdId, type, id, parseRole(role))
                .ifPresent(assignmentRepo::delete);
    }

    // ── Authorization ────────────────────────────────────────────────────

    /**
     * Household admin, or the subject editing their own profile.
     *
     * <p>The self branch exists because a person's own support needs are theirs
     * to state. It applies only to {@code subjectType = user}: a manual member
     * has no account to authenticate as, so their household admin is the only
     * editor — the same authority model as the rest of their record.</p>
     */
    private void requireCanEditSubject(String householdId, String subjectType, String subjectId, String caller) {
        String type = requireSubjectType(subjectType);
        String me = normalizeEmail(caller);
        if ("user".equals(type) && me != null && me.equals(normalizeEmail(subjectId))) {
            // Still must belong to this household — self is not a bypass.
            access.requireCanReadHousehold(caller, householdId);
            return;
        }
        access.requireCanAdminHousehold(caller, householdId);
    }

    // ── Mapping + validation ─────────────────────────────────────────────

    private SupportProfileDto toDto(EmergencySupportProfile p) {
        return new SupportProfileDto(
                p.getSubjectType(), p.getSubjectId(),
                p.isNeedsEvacuationAssistance(), p.isCannotUseStairs(), p.getMobilityNote(),
                List.copyOf(p.getCommunicationNeeds()), p.getPreferredLanguage(),
                p.isPowerDependentEquipment(), p.getEquipmentNote(),
                p.isRefrigeratedMedication(), p.isCriticalMedication(),
                p.isAccessibleTransportNeeded(),
                p.isServiceAnimal(), p.getServiceAnimalNote(),
                p.getSupportNote(), p.getUpdatedAt());
    }

    private SupportAssignmentDto toDto(EmergencySupportAssignment a) {
        return new SupportAssignmentDto(
                a.getSubjectType(), a.getSubjectId(),
                a.getRole() == null ? null : a.getRole().name(),
                a.getHelperType() == null ? null : a.getHelperType().name(),
                a.getHelperUserEmail(), a.getHelperContactId(),
                a.getHelperName(), a.getHelperNote(), a.getAssignedAt());
    }

    private String resolveMemberName(String email) {
        Optional<UserInfo> u = userInfoRepo.findByUserEmailIgnoreCase(email);
        return u.map(x -> {
            String first = x.getUserFirstName();
            String last = x.getUserLastName();
            String joined = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
            return joined.isEmpty() ? null : clamp(joined, 160);
        }).orElse(null);
    }

    /**
     * Unknown values are DROPPED rather than stored.
     *
     * <p>A value the enum does not define would render as nothing and read as a
     * need nobody recorded — and this is the one field where a silent unknown
     * could hide a communication requirement.</p>
     */
    private static List<String> sanitizeNeeds(List<String> raw) {
        if (raw == null) return new ArrayList<>();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String key = s.trim().toUpperCase(Locale.ROOT);
            if (COMMUNICATION_NEEDS.contains(key)) out.add(key);
        }
        return new ArrayList<>(out);
    }

    private static String requireSubjectType(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!SUBJECT_TYPES.contains(t)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "subjectType must be 'user' or 'manual'");
        }
        return t;
    }

    private static EmergencySupportAssignment.Role parseRole(String raw) {
        try {
            return EmergencySupportAssignment.Role.valueOf(
                    raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role must be PRIMARY or BACKUP");
        }
    }

    private static EmergencySupportAssignment.HelperType parseHelperType(String raw) {
        try {
            return EmergencySupportAssignment.HelperType.valueOf(
                    raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "helperType must be MEMBER or CONTACT");
        }
    }

    /** A user subject is an email and normalizes like one; a manual id does not. */
    private static String normalizeId(String subjectType, String subjectId) {
        if (subjectId == null) return null;
        String t = normalizeType(subjectType);
        return "user".equals(t) ? subjectId.trim().toLowerCase(Locale.ROOT) : subjectId.trim();
    }

    private static String normalizeType(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeEmail(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static String clamp(String raw, int max) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
