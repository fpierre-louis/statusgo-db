package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.readiness.ReadinessAction;
import io.sitprep.sitprepapi.practice.PracticeContent.Node;
import io.sitprep.sitprepapi.practice.PracticeContent.Outcome;
import io.sitprep.sitprepapi.practice.PracticeContent.Skill;
import io.sitprep.sitprepapi.practice.PracticeContent.TagCopy;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cross-references the per-file validator cannot see (Practice redesign
 * BE-04). Every skill's scenario nodes must exist, its "confused with" skills
 * must exist, and every drill a skill or a scenario's {@code OPEN_DRILL} step
 * points at must be in the cross-repo contract.
 *
 * <p>The contract ({@value #CONTRACT}) is generated from the FE drill catalog
 * and checked in both repos: the FE test fails if the file stops matching the
 * catalog, this check fails if reviewed content points at a drill the file
 * doesn't list. Before it, a renamed drill silently broke a reviewed
 * scenario's next step (PRACTICE_AUDIT §3).</p>
 */
public final class PracticeRegistryCheck {

    public static final String CONTRACT = "/practice/contract/practiceContract.json";

    private PracticeRegistryCheck() {}

    record ContractFile(Integer schema, String source, List<ContractDrill> drills) {}
    record ContractDrill(String id, String guideSlug) {}

    /** The drill ids in the checked-in contract. */
    public static Set<String> contractDrillIds() {
        try (InputStream in = PracticeRegistryCheck.class.getResourceAsStream(CONTRACT)) {
            if (in == null) throw new IllegalStateException("missing " + CONTRACT);
            ContractFile f = new ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(in, ContractFile.class);
            Set<String> ids = new HashSet<>();
            if (f.drills() != null) f.drills().forEach(d -> { if (d != null && d.id() != null) ids.add(d.id()); });
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("Practice contract unreadable: " + CONTRACT, e);
        }
    }

    /** Every broken cross-reference across {@code versions}; empty when the registry is whole. */
    public static List<String> problems(List<Version> versions, Set<String> drillIds) {
        List<String> out = new ArrayList<>();
        Set<String> skillKeys = new HashSet<>();
        Set<String> scenarioNodes = new HashSet<>();
        for (Version v : versions) {
            if (v.kind() == PracticeKind.SKILL) skillKeys.add(v.key());
            if (v.kind() == PracticeKind.ADULT_SCENARIO && v.body() != null && v.body().scenario() != null
                    && v.body().scenario().nodes() != null) {
                for (Node n : v.body().scenario().nodes()) if (n != null) scenarioNodes.add(v.key() + "#" + n.key());
            }
        }
        for (Version v : versions) {
            String at = v.key() + " v" + v.version();
            if (v.body() == null) continue;
            if (v.body().tags() != null) {
                for (Map.Entry<String, TagCopy> e : v.body().tags().entrySet()) {
                    TagCopy t = e.getValue();
                    if (t != null) drillRef(t.action(), t.params(), drillIds, at + " tag " + e.getKey(), out);
                }
            }
            if (v.body().scenario() != null && v.body().scenario().outcomes() != null) {
                for (Outcome o : v.body().scenario().outcomes()) {
                    if (o != null) drillRef(o.defaultAction(), o.defaultParams(), drillIds, at + " outcome " + o.key(), out);
                }
            }
            if (v.body().activity() != null) {
                drillRef(v.body().activity().nextAction(), v.body().activity().nextParams(), drillIds, at + " activity", out);
            }
            Skill k = v.body().skill();
            if (k == null) continue;
            if (k.scenarioNodes() != null) for (String ref : k.scenarioNodes()) {
                if (!scenarioNodes.contains(ref)) out.add(at + ": scenario node " + ref + " does not exist");
            }
            if (k.drills() != null) for (String d : k.drills()) {
                if (!drillIds.contains(d)) out.add(at + ": drill " + d + " is not in the Practice contract");
            }
            if (k.confusedWith() != null) for (String c : k.confusedWith()) {
                if (c.equals(v.key())) out.add(at + ": a skill cannot be confused with itself");
                else if (!skillKeys.contains(c)) out.add(at + ": confusedWith " + c + " is not a skill");
            }
        }
        return out;
    }

    private static void drillRef(ReadinessAction action, Map<String, String> params, Set<String> drillIds,
                                 String at, List<String> out) {
        if (action != ReadinessAction.OPEN_DRILL) return;
        String id = params == null ? null : params.get("drillId");
        if (id == null) out.add(at + ": OPEN_DRILL without a drillId");
        else if (!drillIds.contains(id)) out.add(at + ": OPEN_DRILL drill " + id + " is not in the Practice contract");
    }
}
