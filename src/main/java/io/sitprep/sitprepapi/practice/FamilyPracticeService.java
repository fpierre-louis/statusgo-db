package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.ActivityCardDto;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.ActivityDetailDto;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.ActivityDto;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.CatalogDto;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.CompletionDto;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.ItemDto;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.PileDto;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.ProgressDto;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.ProgressItemDto;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.Entry;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.StartCheck;
import io.sitprep.sitprepapi.practice.PracticeContent.Activity;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import io.sitprep.sitprepapi.practice.ScenarioDtos.AlertHeadsUpDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.NextStepDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.SourceDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.SuppressionDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Family Practice catalog and household-only, idempotent completion ledger. */
@Service
public class FamilyPracticeService {

    private final PracticeAvailabilityService availability;
    private final PracticeSuppressionService suppression;
    private final FamilyPracticeCompletionRepo completionRepo;
    private final GroupRepo groupRepo;
    private final HouseholdAccessService access;
    private final Clock clock;

    @Autowired
    public FamilyPracticeService(PracticeAvailabilityService availability, PracticeSuppressionService suppression,
                                 FamilyPracticeCompletionRepo completionRepo, GroupRepo groupRepo,
                                 HouseholdAccessService access) {
        this(availability, suppression, completionRepo, groupRepo, access, Clock.systemUTC());
    }

    FamilyPracticeService(PracticeAvailabilityService availability, PracticeSuppressionService suppression,
                          FamilyPracticeCompletionRepo completionRepo, GroupRepo groupRepo,
                          HouseholdAccessService access, Clock clock) {
        this.availability = availability;
        this.suppression = suppression;
        this.completionRepo = completionRepo;
        this.groupRepo = groupRepo;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CatalogDto catalog(String callerEmail, String householdId) {
        String email = normalize(callerEmail);
        String hid = householdScope(email, householdId);
        SuppressionDto waiting = SuppressionDto.of(suppression.check(hid, email).orElse(null));
        Map<String, Instant> completed = latestCompletions(hid);
        List<ActivityCardDto> cards = new ArrayList<>();
        for (Entry entry : availability.startable(PracticeKind.FAMILY_ACTIVITY)) {
            Version v = entry.version();
            cards.add(new ActivityCardDto(v.key(), v.version(), v.body().title(), v.body().summary(),
                    v.body().estimatedMinutes(), safe(v.body().ageGuidance()), entry.preview(), completed.get(v.key())));
        }
        return new CatalogDto(FamilyPracticeDtos.SCHEMA_VERSION, availability.practiceEnabled(), waiting, cards,
                waiting == null ? AlertHeadsUpDto.of(suppression.headsUp(hid).orElse(null)) : null);
    }

    @Transactional(readOnly = true)
    public ActivityDetailDto detail(String activityKey, String callerEmail, String householdId) {
        String email = normalize(callerEmail);
        String hid = householdScope(email, householdId);
        StartCheck start = availability.checkStart(activityKey);
        Version v = familyVersion(start);
        FamilyPracticeCompletion prior = completionRepo
                .findByHouseholdIdAndActivityKeyAndContentVersion(hid, v.key(), v.version()).orElse(null);
        SuppressionDto waiting = SuppressionDto.of(suppression.check(hid, email).orElse(null));
        return new ActivityDetailDto(FamilyPracticeDtos.SCHEMA_VERSION, v.key(), v.version(), v.contentHash(),
                v.body().title(), v.body().summary(), v.body().objective(), v.body().estimatedMinutes(),
                safe(v.body().ageGuidance()), v.body().facilitation(),
                v.body().sources().stream().map(s -> new SourceDto(s.label(), s.url())).toList(),
                activityDto(v.body().activity()), start.entry().preview(), waiting,
                prior == null ? null : prior.getCompletedAt(),
                waiting == null ? AlertHeadsUpDto.of(suppression.headsUp(hid).orElse(null)) : null);
    }

    /** Idempotent per household + activity + version. No picks or child data enter this method. */
    public CompletionDto complete(String activityKey, String callerEmail, String householdId,
                                  int contentVersion, String contentHash) {
        String email = normalize(callerEmail);
        String hid = householdScope(email, householdId);
        requireCalm(hid, email);
        StartCheck start = availability.checkStart(activityKey);
        Version v = familyVersion(start);
        if (contentVersion != v.version() || contentHash == null || !contentHash.equals(v.contentHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This activity changed; reload before recording it");
        }
        if (start.entry().preview()) {
            // A preview build rehearses unreviewed content and records nothing.
            // Publishing keeps the same version and hash, so a stored preview
            // row would turn into a household's real completion on publish.
            return new CompletionDto(FamilyPracticeDtos.SCHEMA_VERSION, hid, v.key(), v.version(), v.contentHash(),
                    clock.instant(), List.copyOf(v.body().activity().completionTags()), nextStep(v.body().activity()));
        }
        FamilyPracticeCompletion prior = completionRepo
                .findByHouseholdIdAndActivityKeyAndContentVersion(hid, v.key(), v.version()).orElse(null);
        if (prior != null) return completionDto(prior, v);

        FamilyPracticeCompletion row = new FamilyPracticeCompletion();
        row.setHouseholdId(hid);
        row.setActivityKey(v.key());
        row.setContentVersion(v.version());
        row.setContentHash(v.contentHash());
        row.setCompletedAt(clock.instant());
        row.setCompletedByEmail(email);
        row.setDebriefTags(new ArrayList<>(v.body().activity().completionTags()));
        try {
            return completionDto(completionRepo.saveAndFlush(row), v);
        } catch (DataIntegrityViolationException raced) {
            return completionRepo.findByHouseholdIdAndActivityKeyAndContentVersion(hid, v.key(), v.version())
                    .map(existing -> completionDto(existing, v))
                    .orElseThrow(() -> raced);
        }
    }

    @Transactional(readOnly = true)
    public ProgressDto progress(String householdId, String callerEmail) {
        String email = normalize(callerEmail);
        String hid = householdScope(email, householdId);
        Map<String, ProgressItemDto> byKey = new LinkedHashMap<>();
        for (FamilyPracticeCompletion row : completionRepo.findByHouseholdIdOrderByCompletedAtDesc(hid)) {
            ProgressItemDto current = byKey.get(row.getActivityKey());
            int count = current == null ? 1 : current.completedVersions() + 1;
            Instant last = current == null || row.getCompletedAt().isAfter(current.lastCompletedAt())
                    ? row.getCompletedAt() : current.lastCompletedAt();
            byKey.put(row.getActivityKey(), new ProgressItemDto(row.getActivityKey(), count, last));
        }
        return new ProgressDto(FamilyPracticeDtos.SCHEMA_VERSION, hid, List.copyOf(byKey.values()));
    }

    private Map<String, Instant> latestCompletions(String hid) {
        Map<String, Instant> out = new LinkedHashMap<>();
        for (FamilyPracticeCompletion row : completionRepo.findByHouseholdIdOrderByCompletedAtDesc(hid)) {
            out.putIfAbsent(row.getActivityKey(), row.getCompletedAt());
        }
        return out;
    }

    private static ActivityDto activityDto(Activity a) {
        return new ActivityDto(a.type(), a.prompt(), a.reminderQuestion(), a.recapTitle(), a.recapBody(),
                a.piles().stream().map(p -> new PileDto(p.key(), p.label())).toList(),
                a.items().stream().map(i -> new ItemDto(i.key(), i.label(), i.artKey())).toList(),
                nextStep(a), a.finishLabel(), a.summaryTitle(), a.summaryBody());
    }

    private static CompletionDto completionDto(FamilyPracticeCompletion row, Version v) {
        return new CompletionDto(FamilyPracticeDtos.SCHEMA_VERSION, row.getHouseholdId(), row.getActivityKey(),
                row.getContentVersion(), row.getContentHash(), row.getCompletedAt(),
                List.copyOf(row.getDebriefTags()), nextStep(v.body().activity()));
    }

    private static NextStepDto nextStep(Activity a) {
        return new NextStepDto(a.nextAction(), a.nextParams() == null ? Map.of() : a.nextParams(),
                a.nextPrompt(), a.nextLabel(), a.nextNote());
    }

    private Version familyVersion(StartCheck start) {
        if (!start.allowed()) throw startDenied(start);
        Version v = start.entry().version();
        if (v.kind() != PracticeKind.FAMILY_ACTIVITY) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Family activity not found");
        }
        return v;
    }

    private String householdScope(String email, String householdId) {
        if (householdId == null || householdId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "householdId is required");
        }
        groupRepo.findByGroupId(householdId)
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found"));
        access.requireCanReadHousehold(email, householdId);
        return householdId;
    }

    private void requireCalm(String hid, String email) {
        if (suppression.check(hid, email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ScenarioService.WAITING);
        }
    }

    private static ResponseStatusException startDenied(StartCheck start) {
        return switch (start.denial()) {
            case DISABLED, PRACTICE_OFF -> new ResponseStatusException(HttpStatus.GONE, ScenarioService.WITHDRAWN);
            case NOT_FOUND, NOT_PUBLISHED -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Family activity not found");
        };
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
