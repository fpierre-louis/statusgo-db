package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.messaging.Message;
import io.sitprep.sitprepapi.dto.CheckInRollupDto;
import io.sitprep.sitprepapi.service.LiveActivityStateBuilder.Situation;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Live Activity contract (live_activities GAMEPLAN §3.2): the server's
 * {@code content-state} must decode as the Swift
 * {@code SituationAttributes.ContentState} with Swift's DEFAULT Codable.
 *
 * <p>Nothing else would catch a drift. APNs accepts the push, FCM returns an
 * id, and the device silently drops an update whose content-state does not
 * decode — the card just goes stale.</p>
 *
 * <p>The fixture lives here AND beside the Swift file
 * ({@code ios/App/Shared/SituationContentState.fixture.json}). The Swift file
 * and the app's copy are in the other repo, so those two checks run only where
 * that repo sits beside this one (a dev machine); the builder-vs-fixture check
 * always runs, Heroku included.</p>
 */
class LiveActivityContentStateContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURE = Paths.get("src/test/resources/fixtures/live-activity-situation-content-state.json");
    private static final Path APP_SHARED = Paths.get("../SitPrep FE/ios/App/Shared");
    private static final Path SWIFT = APP_SHARED.resolve("ActivityAttributes.swift");
    private static final Path APP_FIXTURE = APP_SHARED.resolve("SituationContentState.fixture.json");

    /** Swift optionals: decodeIfPresent, so null or absent decodes to nil. */
    private static final Set<String> OPTIONAL = Set.of("pickup", "expiresAtEpoch", "note",
            "planStep", "canChange", "changePath");

    private static LinkedHashMap<String, Object> fixture() throws Exception {
        return JSON.readValue(Files.readString(FIXTURE), new TypeReference<>() {});
    }

    private static Situation situation(boolean plan, Integer pickup, int help) {
        CheckInRollupDto r = new CheckInRollupDto("hh-1", "Plouis household", true,
                Instant.parse("2026-10-13T15:00:00Z"), 6, 3 + help, 3, help, 0, 3 - help, List.of(),
                Instant.parse("2026-10-15T15:00:00Z"));
        return new Situation("hh-1", "Plouis household", "Household", r,
                Instant.parse("2026-10-13T15:00:00Z"), Instant.parse("2026-10-15T15:00:00Z"), plan, pickup);
    }

    @Test
    void theBuilderEmitsExactlyTheFixtureKeys_inTheSwiftOrder() throws Exception {
        Map<String, Object> state = LiveActivityStateBuilder.contentState(
                "active", situation(true, 1, 1), Instant.parse("2026-10-13T15:10:00Z"), null);

        assertThat(new ArrayList<>(state.keySet())).containsExactlyElementsOf(fixture().keySet());
        assertThat(new ArrayList<>(state.keySet())).containsExactlyElementsOf(LiveActivityStateBuilder.CONTENT_STATE_KEYS);
    }

    @Test
    void everyNonOptionalKeyIsAlwaysPresentWithTheRightType() {
        Instant now = Instant.parse("2026-10-13T15:10:00Z");
        List<Map<String, Object>> states = List.of(
                LiveActivityStateBuilder.contentState("active", situation(false, null, 0), now, null),
                LiveActivityStateBuilder.contentState("active", situation(true, 0, 2), now, null),
                LiveActivityStateBuilder.contentState("ended", situation(true, 2, 1), now, "Ended by you after 17 min."));
        for (Map<String, Object> s : states) {
            for (String key : LiveActivityStateBuilder.CONTENT_STATE_KEYS) {
                assertThat(s).containsKey(key);
                if (OPTIONAL.contains(key)) continue;
                assertThat(s.get(key)).as(key).isNotNull();
            }
            assertThat(s.get("phase")).isInstanceOf(String.class);
            assertThat(s.get("plan")).isInstanceOf(Boolean.class);
            for (String intKey : List.of("total", "accounted", "safe", "help", "injured", "asOfEpoch")) {
                assertThat(s.get(intKey)).as(intKey).isInstanceOfAny(Integer.class, Long.class);
            }
        }
    }

    @Test
    void theSwiftContentStateHasExactlyTheseProperties() throws Exception {
        assumeTrue(Files.exists(SWIFT), "the app repo is not beside this one");
        String swift = Files.readString(SWIFT);
        int start = swift.indexOf("public struct ContentState");
        int end = swift.indexOf("public init(", start);
        assertThat(start).as("ContentState in ActivityAttributes.swift").isPositive();
        Matcher m = Pattern.compile("public let (\\w+): ([\\w?]+)").matcher(swift.substring(start, end));

        List<String> names = new ArrayList<>();
        Map<String, String> types = new LinkedHashMap<>();
        while (m.find()) {
            names.add(m.group(1));
            types.put(m.group(1), m.group(2));
        }
        assertThat(names).containsExactlyElementsOf(fixture().keySet());
        for (Map.Entry<String, String> e : types.entrySet()) {
            boolean optional = e.getValue().endsWith("?");
            assertThat(optional).as(e.getKey() + " optionality").isEqualTo(OPTIONAL.contains(e.getKey()));
            String base = e.getValue().replace("?", "");
            assertThat(base).as(e.getKey() + " has no Date (rule 1)").isIn("Int", "Bool", "String");
        }
    }

    @Test
    void theAppsCopyOfTheFixtureIsThisOne() throws Exception {
        assumeTrue(Files.exists(APP_FIXTURE), "the app repo is not beside this one");
        assertThat(JSON.readTree(Files.readString(APP_FIXTURE))).isEqualTo(JSON.readTree(Files.readString(FIXTURE)));
    }

    @Test
    void theWireMessageCarriesTheHeadersTheTokensAndTheApsKeys() throws Exception {
        Instant now = Instant.parse("2026-10-13T15:10:00Z");
        Map<String, Object> state = LiveActivityStateBuilder.contentState("active", situation(true, 1, 1), now, null);
        Map<String, Object> aps = LiveActivityPushService.aps("update", state, now,
                now.plus(LiveActivityPushService.STALE_AFTER), null, 1, null);
        Message msg = LiveActivityPushService.buildMessage("fcm-device-token", "abcdef0123456789",
                LiveActivityPushService.DEFAULT_APNS_TOPIC, aps, 5);

        JsonNode wire = JSON.readTree(GsonFactory.getDefaultInstance().toString(msg));

        assertThat(wire.path("token").asText()).isEqualTo("fcm-device-token");
        JsonNode apns = wire.path("apns");
        assertThat(apns.path("live_activity_token").asText()).isEqualTo("abcdef0123456789");
        assertThat(apns.path("headers").path("apns-push-type").asText()).isEqualTo("liveactivity");
        assertThat(apns.path("headers").path("apns-topic").asText()).isEqualTo("app.sitprep.ios.push-type.liveactivity");
        assertThat(apns.path("headers").path("apns-priority").asText()).isEqualTo("5");
        assertThat(wire.has("notification")).as("no visible notification block").isFalse();

        JsonNode a = apns.path("payload").path("aps");
        assertThat(a.path("event").asText()).isEqualTo("update");
        assertThat(a.path("timestamp").asLong()).isEqualTo(now.getEpochSecond());
        assertThat(a.path("stale-date").asLong()).isEqualTo(now.getEpochSecond() + 600);
        assertThat(a.has("dismissal-date")).isFalse();
        assertThat(a.has("alert")).isFalse();
        assertThat(a.path("relevance-score").asInt()).isEqualTo(100);

        JsonNode cs = a.path("content-state");
        for (String key : LiveActivityStateBuilder.CONTENT_STATE_KEYS) {
            if (OPTIONAL.contains(key)) continue;
            assertThat(cs.has(key)).as("content-state." + key).isTrue();
        }
        assertThat(cs.path("phase").asText()).isEqualTo("active");
        assertThat(cs.path("plan").asBoolean()).isTrue();
        assertThat(cs.path("total").asInt()).isEqualTo(6);
        assertThat(cs.path("accounted").asInt()).isEqualTo(4);
        assertThat(cs.path("help").asInt()).isEqualTo(1);
        assertThat(cs.path("pickup").asInt()).isEqualTo(1);
        assertThat(cs.path("expiresAtEpoch").asLong()).isEqualTo(Instant.parse("2026-10-15T15:00:00Z").getEpochSecond());
        assertThat(cs.path("asOfEpoch").asLong()).isEqualTo(now.getEpochSecond());
        // Swift decodes null and absent alike; either is fine for an optional.
        assertThat(cs.path("note").isMissingNode() || cs.path("note").isNull()).isTrue();
        // Every value is a number, bool or string: nothing Swift would read as a Date.
        cs.fields().forEachRemaining(e -> assertThat(e.getValue().isContainerNode()).as(e.getKey()).isFalse());
    }

    @Test
    void anEndCarriesADismissalDateAndAnAlertWithSound() throws Exception {
        Instant now = Instant.parse("2026-10-13T15:10:00Z");
        Map<String, Object> state = LiveActivityStateBuilder.contentState("ended", situation(false, null, 0), now,
                "Ended after 10 min.");
        Map<String, Object> aps = LiveActivityPushService.aps("end", state, now, null,
                now.plus(LiveActivityPushService.DISMISS_AFTER), 0,
                LiveActivityPushService.alert("Plouis household", "Check-in ended · 3 of 6 accounted for"));
        Message msg = LiveActivityPushService.buildMessage("fcm", "abcdef0123456789",
                LiveActivityPushService.DEFAULT_APNS_TOPIC, aps, 10);

        JsonNode wire = JSON.readTree(GsonFactory.getDefaultInstance().toString(msg));
        JsonNode a = wire.path("apns").path("payload").path("aps");
        assertThat(wire.path("apns").path("headers").path("apns-priority").asText()).isEqualTo("10");
        assertThat(a.path("event").asText()).isEqualTo("end");
        assertThat(a.path("dismissal-date").asLong()).isEqualTo(now.getEpochSecond() + 900);
        assertThat(a.has("stale-date")).isFalse();
        assertThat(a.path("alert").path("title").asText()).isEqualTo("Plouis household");
        assertThat(a.path("alert").path("body").asText()).isEqualTo("Check-in ended · 3 of 6 accounted for");
        assertThat(a.path("alert").path("sound").asText()).isEqualTo("default");
        assertThat(a.path("content-state").path("phase").asText()).isEqualTo("ended");
        assertThat(a.path("content-state").path("note").asText()).isEqualTo("Ended after 10 min.");
        assertThat(a.path("relevance-score").asInt()).isEqualTo(50);
    }
}
