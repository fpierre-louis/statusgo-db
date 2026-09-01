package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WHAT A DTO ON A PUBLIC ROUTE IS ALLOWED TO CONTAIN.
 *
 * <h2>Why this exists</h2>
 *
 * On 2026-08-31 {@code GET /api/ask/questions} — {@code permitAll}, no token —
 * returned a real personal email address and a 7-decimal coordinate pair for
 * every question. It had done so since the endpoint shipped. Nothing failed,
 * because nothing was asserting anything about it: the DTO simply had the
 * fields, and the route simply was public, and the two facts never met.
 *
 * {@code AskDtoPrivacyTest} now pins the Ask family specifically. <b>This test
 * is the general case</b>: it exists so the NEXT one is caught by CI rather than
 * by somebody thinking to look.
 *
 * <h2>How it works, and why it is not a blanket ban</h2>
 *
 * A blanket "no emails on public DTOs" rule would be wrong and would be
 * disabled within a month. {@code PlanActivationDtos} legitimately carries
 * recipient emails, phone numbers and emergency-contact details: an activation
 * id is a 122-bit random UUID and the URL is a CAPABILITY the owner shares
 * deliberately with the people who need it. Redacting it would break the
 * feature.
 *
 * So the rule is <b>declared exposure</b>, not prohibition. Every sensitive
 * field on a public DTO must appear in {@link #ALLOWED} with a reason. A field
 * that is not declared fails the build, which forces the decision to be made by
 * a person once, rather than by default forever.
 *
 * <h2>Two things it deliberately checks the hard way</h2>
 *
 * <b>It reads the route list out of SecurityConfig's source</b> rather than
 * keeping its own copy. A duplicated allowlist is the exact defect this
 * codebase has logged twice (T-67, and CLAUDE.md's MAX_RADIUS_MI row): the copy
 * drifts and the drift is invisible. If a new {@code permitAll} route appears
 * whose payload type is not covered here, {@link #everyPublicRouteHasACoveredPayload()}
 * says so by name.
 *
 * <b>It asserts on SERIALISED JSON, not on the field list.</b> Two of the three
 * Ask fixes were annotations — {@code @JsonIgnore} on a join key,
 * {@code Access.WRITE_ONLY} on coordinates — so a field-presence check would
 * have passed with the leak wide open.
 */
class PublicPayloadPrivacyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Field-name fragments that mean "this identifies or locates a person". */
    private static final List<String> SENSITIVE = List.of(
            "email", "phone", "latitude", "longitude", "address",
            "lastknownlat", "lastknownlng", "medical"
    );

    /**
     * Sensitive fields that are ALLOWED on a public payload, and why.
     *
     * <p>Key is {@code SimpleClassName.fieldName}. Adding a line here is a
     * decision; the test exists to make sure one is taken.</p>
     */
    private static final Map<String, String> ALLOWED = new LinkedHashMap<>() {{
        // Capability URL. The activation id is a random UUID (@UuidGenerator)
        // and the owner shares the link deliberately with the people who must
        // act on it. An evacuation plan whose emergency contacts were redacted
        // would not be an evacuation plan.
        put("AckDto.recipientEmail", "capability URL — owner-shared activation link");
        put("EmergencyContactSnapshotDto.phone", "capability URL — the contact IS the content");
        put("EmergencyContactSnapshotDto.email", "capability URL — the contact IS the content");
        put("EmergencyContactSnapshotDto.address", "capability URL — the contact IS the content");
        put("EmergencyContactSnapshotDto.medicalInfo", "capability URL — carried for responders");
        put("MeetingPlaceSnapshotDto.address", "capability URL — where to go");
        put("MeetingPlaceSnapshotDto.phoneNumber", "capability URL — who to call there");
        put("EvacuationPlanSnapshotDto.shelterAddress", "capability URL — where to go");
        put("EvacuationPlanSnapshotDto.shelterPhoneNumber", "capability URL — who to call there");
        // Invite preview. Group ids are UUIDs, so this needs the link too, and
        // the address is what someone deciding whether to join is looking at.
        // The DTO is otherwise sanitised — no member or admin emails.
        put("GroupPreviewDto.address", "invite preview — the thing being judged");
        put("GroupPreviewDto.latitude", "invite preview — map pin for the same");
        put("GroupPreviewDto.longitude", "invite preview — map pin for the same");
    }};

    /** The Ask family: the ones that leaked, now asserted to carry nothing. */
    private static final List<Class<?>> ASK_PAYLOADS = List.of(
            AskQuestionDto.class, AskAnswerDto.class, AskTipDto.class, AskSearchHitDto.class
    );

    // ------------------------------------------------------------------
    // 1 · Nothing sensitive ships undeclared
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no Ask payload SERIALISES a sensitive value, whatever its fields are named")
    void askPayloadsAreClean() throws Exception {
        // ASSERT ON THE JSON, NOT THE FIELD LIST — the whole reason this file
        // exists. My first draft of this method reflected field NAMES and
        // failed on AskQuestionDto.latitude, which is present and
        // Access.WRITE_ONLY and never ships. A name check gets that backwards
        // in both directions: it flags a field that is safe, and it would pass
        // a field that is named innocuously and leaks.
        //
        // So: stuff every sensitive field with a unique sentinel, serialise,
        // and assert no sentinel survives. @JsonIgnore and WRITE_ONLY are then
        // measured rather than trusted.
        List<String> offenders = new ArrayList<>();
        for (Class<?> c : ASK_PAYLOADS) {
            Object dto = c.getDeclaredConstructor().newInstance();
            Map<String, String> sentinels = new LinkedHashMap<>();
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                String lower = f.getName().toLowerCase(Locale.ROOT);
                if (SENSITIVE.stream().noneMatch(lower::contains)) continue;
                String sentinel = "SENTINEL-" + c.getSimpleName() + "-" + f.getName();
                f.setAccessible(true);
                if (f.getType() == String.class) {
                    f.set(dto, sentinel);
                    sentinels.put(f.getName(), sentinel);
                } else if (f.getType() == Double.class || f.getType() == double.class) {
                    // A distinctive coordinate: 7 decimals, like the real leak.
                    f.set(dto, 40.4376647);
                    sentinels.put(f.getName(), "40.4376647");
                }
            }
            String json = mapper.writeValueAsString(dto);
            sentinels.forEach((field, sentinel) -> {
                String key = c.getSimpleName() + "." + field;
                if (json.contains(sentinel) && !ALLOWED.containsKey(key)) offenders.add(key);
            });
        }
        assertThat(offenders)
                .as("an anonymous Ask payload SERIALISED a person-identifying value; "
                        + "if it is genuinely required, add it to ALLOWED with a reason")
                .isEmpty();
    }

    @Test
    @DisplayName("the search hit's email join key never serialises")
    void searchHitJoinKeyNeverSerialises() throws Exception {
        AskSearchHitDto h = new AskSearchHitDto();
        h.setAuthorEmail("leak@example.com");
        assertThat(mapper.writeValueAsString(h)).doesNotContain("leak@example.com");
    }

    @Test
    @DisplayName("Ask coordinates bind but never serialise")
    void askCoordinatesAreWriteOnly() throws Exception {
        AskQuestionDto q = new AskQuestionDto();
        q.setLatitude(40.4376647);
        q.setLongitude(-111.8804517);
        assertThat(mapper.writeValueAsString(q))
                .as("a 7-decimal pair is ~1cm precision on where the author was standing")
                .doesNotContain("40.4376647", "-111.8804517");

        AskTipDto t = new AskTipDto();
        t.setLatitude(40.4376647);
        assertThat(mapper.writeValueAsString(t)).doesNotContain("40.4376647");
    }

    // ------------------------------------------------------------------
    // 2 · The route list is READ, not copied
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every permitAll route's payload family is covered by this file")
    void everyPublicRouteHasACoveredPayload() throws Exception {
        Set<String> routes = permitAllRoutesFromSecurityConfig();
        assertThat(routes)
                .as("SecurityConfig.java could not be parsed — if the config moved, "
                        + "fix this reader rather than deleting the assertion")
                .isNotEmpty();

        // Families this file reasons about, by route prefix. A new public route
        // outside these must be triaged: either its payload has no sensitive
        // field (add the prefix), or it does (add its fields to ALLOWED).
        List<String> covered = List.of(
                "/api/ask/",                    // asserted clean above
                "/api/plans/activations",       // capability URL, declared in ALLOWED
                "/api/groups/*/preview",        // invite preview, declared in ALLOWED
                "/api/public/**",               // signed-token opt-out
                "/api/billing/webhook",         // Stripe, no payload of ours
                "/api/community/map",           // POIs; plots no individuals by design
                "/api/retail/products",         // catalogue
                "/api/readiness/assessment",    // question bank + scoring
                "/api/agency/requests",         // inbound only
                "/**",                          // the OPTIONS preflight rule
                // Both surfaced BY THIS TEST on its first run — neither had ever
                // been triaged, which is exactly what it is for.
                //
                // /actuator/**: permitAll, and nothing is mounted there. Probed
                // live against prod v555 — /actuator, /health, /env, /beans and
                // /configprops all return 500 through the app's own error
                // envelope, i.e. unmapped path. No management.endpoints config
                // exists. Harmless today; it would NOT be if the actuator
                // starter were ever added, so it stays listed rather than
                // silently matched by a wildcard.
                "/actuator/**",
                // /ws/**, /app/**, /topic/**: SockJS + STOMP transport. Not a
                // REST payload; authorisation happens at the STOMP layer.
                "/ws/**", "/app/**", "/topic/**"
        );

        List<String> untriaged = routes.stream()
                .filter(r -> covered.stream().noneMatch(r::startsWith))
                .sorted()
                .toList();

        assertThat(untriaged)
                .as("a new permitAll route exists that this privacy guard has never "
                        + "looked at. Decide what its payload may expose, then add the "
                        + "prefix here (and any sensitive fields to ALLOWED).")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Every field/component name on {@code c} that looks person-identifying. */
    private static Set<String> sensitiveFieldsOf(Class<?> c) {
        Set<String> names = new LinkedHashSet<>();
        if (c.isRecord()) {
            for (RecordComponent rc : c.getRecordComponents()) names.add(rc.getName());
        } else {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                names.add(f.getName());
            }
        }
        Set<String> hits = new LinkedHashSet<>();
        for (String n : names) {
            String lower = n.toLowerCase(Locale.ROOT);
            if (SENSITIVE.stream().anyMatch(lower::contains)) hits.add(n);
        }
        return hits;
    }

    /**
     * The permitAll patterns, read out of SecurityConfig's SOURCE.
     *
     * <p>Reading source in a test is unusual. The alternative is keeping a copy
     * of the route list here, and a duplicated allowlist across two files is
     * precisely the defect logged as T-67 — the copy drifts, and the drift is
     * silent. Parsing is fragile in a way that FAILS LOUDLY; a stale copy is
     * fragile in a way that does not.</p>
     */
    private static Set<String> permitAllRoutesFromSecurityConfig() throws Exception {
        Path p = Path.of("src/main/java/io/sitprep/sitprepapi/config/SecurityConfig.java");
        if (!Files.exists(p)) return Set.of();
        String src = Files.readString(p);
        Pattern re = Pattern.compile("requestMatchers\\(\\s*(?:HttpMethod\\.[A-Z]+\\s*,\\s*)?\"([^\"]+)\"[^)]*\\)\\s*\\.permitAll");
        Matcher m = re.matcher(src);
        Set<String> out = new LinkedHashSet<>();
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
