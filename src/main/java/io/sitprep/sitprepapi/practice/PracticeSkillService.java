package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.Entry;
import io.sitprep.sitprepapi.practice.PracticeContent.Skill;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The skill registry, read-only (Practice redesign BE-04): the instructional
 * spine scenarios, drills, check-ins and if-then plan lines point at. It
 * serves skills exactly as reviewed — PUBLISHED only, unreviewed versions only
 * under the existing local preview gate — and carries no household state:
 * "Practiced …", "Ready for a refresh" and recommendations come later
 * (BE-05/06), from what a household actually did, never from a skill alone.
 */
@Service
public class PracticeSkillService {

    private static final Logger log = LoggerFactory.getLogger(PracticeSkillService.class);

    public static final int SCHEMA_VERSION = 1;

    private final PracticeAvailabilityService availability;
    private final PracticeCatalog catalog;

    public PracticeSkillService(PracticeAvailabilityService availability, PracticeCatalog catalog) {
        this.availability = availability;
        this.catalog = catalog;
    }

    /** A broken cross-reference is loud at boot (tests are where it fails). */
    @PostConstruct
    void checkRegistry() {
        try {
            List<String> problems = PracticeRegistryCheck.problems(catalog.all(), PracticeRegistryCheck.contractDrillIds());
            for (String p : problems) log.error("Practice registry: {}", p);
        } catch (RuntimeException e) {
            log.error("Practice registry check could not run", e);
        }
    }

    public record SkillDto(String key, int version, boolean preview, String hazard, String title, String summary,
                           String cue, String action, String why, String physicalRehearsal, String reviewTier,
                           List<String> scenarioNodes, List<String> drills, List<String> checkInPrompts,
                           List<String> confusedWith, PracticeContent.IfThen ifThen,
                           List<PracticeContent.Source> sources) {}

    public record SkillsDto(int schemaVersion, List<SkillDto> skills) {}

    /** Startable skills, optionally for one hazard, in stable key order. */
    public SkillsDto list(String hazard) {
        List<SkillDto> out = new ArrayList<>();
        for (Entry e : availability.startable(PracticeKind.SKILL)) {
            Version v = e.version();
            Skill k = v.body().skill();
            if (k == null) continue;
            if (hazard != null && !hazard.equals(k.hazard())) continue;
            out.add(new SkillDto(v.key(), v.version(), e.preview(), k.hazard(), v.body().title(), v.body().summary(),
                    k.cue(), k.action(), k.why(),
                    k.physicalRehearsal() == null ? null : k.physicalRehearsal().name(),
                    k.reviewTier() == null ? null : k.reviewTier().name(),
                    nn(k.scenarioNodes()), nn(k.drills()), nn(k.checkInPrompts()), nn(k.confusedWith()),
                    k.ifThen(), nn(v.body().sources())));
        }
        return new SkillsDto(SCHEMA_VERSION, out);
    }

    private static <T> List<T> nn(List<T> l) {
        return l == null ? List.of() : List.copyOf(l);
    }
}
