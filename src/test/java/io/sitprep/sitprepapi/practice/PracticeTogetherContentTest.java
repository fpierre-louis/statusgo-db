package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.ActivityDetailDto;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.Entry;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.StartCheck;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static io.sitprep.sitprepapi.practice.PracticeFixtures.family;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FR-8: TOGETHER content rules, DTO mapping, and the DRAFT pilot. */
class PracticeTogetherContentTest {

    static final Path PILOT = Path.of("src/main/resources/practice/content/together-get-out-and-meet-up/v1.json");

    /** The hash the reviewer must read. Any edit to the pilot fails here first. */
    static final String PILOT_DRAFT_HASH = "sha256:b23c1017ca28138b0d9a16016287209b8a1df5b3221851d139ecee396e256b96";

    static ObjectNode together() {
        try {
            return (ObjectNode) PracticeFixtures.M.readTree(Files.readString(PILOT));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static ObjectNode activity(ObjectNode root) {
        return (ObjectNode) ((ObjectNode) root.get("content")).get("activity");
    }

    static ObjectNode turn(ObjectNode root, int i) {
        return (ObjectNode) activity(root).get("turns").get(i);
    }

    private static List<String> errors(ObjectNode root) {
        return PracticeContentValidator.validate(version(root));
    }

    private static void assertRejected(ObjectNode root, String fragment) {
        assertThat(errors(root)).anySatisfy(e -> assertThat(e).contains(fragment));
    }

    // -- positives ---------------------------------------------------------

    @Test
    void thePilotValidatesAndStaysDraftWithItsSources() {
        ObjectNode root = together();
        assertThat(errors(root)).isEmpty();
        Version v = version(root);
        assertThat(v.publishState()).isEqualTo(PublishState.DRAFT);
        assertThat(v.body().sources()).extracting(PracticeContent.Source::url)
                .anySatisfy(u -> assertThat(u).contains("redcross.org"))
                .anySatisfy(u -> assertThat(u).contains("usfa.fema.gov"))
                .anySatisfy(u -> assertThat(u).contains("nfpa.org"));
        assertThat(v.contentHash()).isEqualTo(PILOT_DRAFT_HASH);
    }

    @Test
    void thePilotIsLoadedByTheCatalogAsDraftOnly() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        assertThat(catalog.rejected()).isEmpty();
        assertThat(catalog.latestStartable("together-get-out-and-meet-up", false)).isEmpty();
        assertThat(catalog.latestStartable("together-get-out-and-meet-up", true)).isPresent();
    }

    @Test
    void thePilotHoldsNoChildDataAndNoAdultOnlyRoleOnAChildStep() {
        PracticeContent.Activity a = version(together()).body().activity();
        assertThat(a.roles()).allSatisfy(r -> assertThat(TogetherRole.parse(r).orElseThrow().adultOnly()).isFalse());
        assertThat(a.turns()).hasSizeBetween(4, 8);
        assertThat(a.turns().get(0).role()).isEqualTo("ADULT");
    }

    @Test
    void anAdultOnlyRoleIsAllowedOnAWithMeTurn() {
        ObjectNode root = together();
        ((ArrayNode) activity(root).get("roles")).add("UTILITIES");
        turn(root, 1).put("role", "UTILITIES");
        assertThat(errors(root)).isEmpty();
    }

    // -- negatives, one rule each -------------------------------------------

    @Test
    void recommendedStyleMustBeOneOfTheThree() {
        ObjectNode root = together();
        activity(root).put("recommendedStyle", "SOLO");
        assertRejected(root, "recommendedStyle");
    }

    @Test
    void anUnknownRoleKeyIsRejected() {
        ObjectNode root = together();
        ((ArrayNode) activity(root).get("roles")).add("CHILD_LEADER");
        assertRejected(root, "unknown role CHILD_LEADER");
    }

    @Test
    void contentCannotMarkARoleAdultOnlyOrChildSafe() {
        // The DTO has no such field and the parser is strict: an "adultOnly" key in content fails.
        ObjectNode root = together();
        activity(root).putObject("roleOverrides").put("MEDICAL", false);
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> version(root)))
                .hasMessageContaining("roleOverrides");
    }

    @Test
    void allFourRhythmsMustAppear() {
        ObjectNode root = together();
        ((ArrayNode) activity(root).get("turns")).remove(5);
        ((ArrayNode) activity(root).get("turns")).remove(4);
        assertRejected(root, "all four rhythms");
    }

    @Test
    void rhythmsMustNotGoBackwards() {
        ObjectNode root = together();
        turn(root, 3).put("rhythm", "WITH_ME");
        turn(root, 2).put("rhythm", "TRY_IT");
        // turn 3 (WITH_ME) now follows a TRY_IT
        assertRejected(root, "goes backwards");
    }

    @Test
    void showMeMustBeLedByTheAdult() {
        ObjectNode root = together();
        turn(root, 0).put("role", "ALL");
        assertRejected(root, "SHOW_ME turn is led by the ADULT");
    }

    @Test
    void tellMeWhyNeedsAnAsk() {
        ObjectNode root = together();
        turn(root, 4).remove("ask");
        assertRejected(root, "TELL_ME_WHY turn needs an ask");
    }

    @Test
    void anAdultOnlyRoleIsNeverOnTryItOrTellMeWhy() {
        for (int i : new int[] {2, 4}) {
            ObjectNode root = together();
            ((ArrayNode) activity(root).get("roles")).add("MEDICAL");
            turn(root, i).put("role", "MEDICAL");
            assertRejected(root, "adult-only role MEDICAL cannot be on a child's step");
        }
    }

    @Test
    void aTurnRoleMustResolve() {
        ObjectNode root = together();
        turn(root, 2).put("role", "READER"); // a real role, but not listed in roles
        assertRejected(root, "must be ADULT, ALL, ANY or one of the listed roles");
        ObjectNode root2 = together();
        turn(root2, 2).put("role", "NOBODY");
        assertRejected(root2, "must be ADULT, ALL, ANY or one of the listed roles");
    }

    @Test
    void turnCountMustBeFourToEight() {
        ObjectNode few = together();
        ArrayNode t = (ArrayNode) activity(few).get("turns");
        while (t.size() > 3) t.remove(t.size() - 1);
        assertRejected(few, "needs 4-8 turns");

        ObjectNode many = together();
        ArrayNode m = (ArrayNode) activity(many).get("turns");
        while (m.size() < 9) m.add(m.get(5).deepCopy());
        assertRejected(many, "needs 4-8 turns");
    }

    @Test
    void lengthLimitsAreEnforced() {
        String long121 = "x".repeat(121);
        String long161 = "x".repeat(161);
        ObjectNode a = together(); turn(a, 0).put("say", long121); assertRejected(a, ".say is longer than 120");
        ObjectNode b = together(); turn(b, 0).put("grownUp", long161); assertRejected(b, ".grownUp is longer than 160");
        ObjectNode c = together(); turn(c, 4).put("ask", long121); assertRejected(c, ".ask is longer than 120");
        ObjectNode d = together(); activity(d).put("remember", long161); assertRejected(d, "remember is longer than 160");
        ObjectNode e = together(); activity(e).put("talkAboutIt", long161); assertRejected(e, "talkAboutIt is longer than 160");
        ObjectNode f = together(); ((ArrayNode) activity(f).get("followUps")).add("y".repeat(81));
        assertRejected(f, "followUps entry is longer than 80");
    }

    @Test
    void followUpsNeedOneToThree() {
        ObjectNode none = together();
        activity(none).putArray("followUps");
        assertRejected(none, "followUps needs 1-3");
        ObjectNode four = together();
        ArrayNode f = (ArrayNode) activity(four).get("followUps");
        f.add("c"); f.add("d");
        assertRejected(four, "followUps needs 1-3");
    }

    @Test
    void copyProhibitionsApplyToEveryTogetherField() {
        ObjectNode a = together(); turn(a, 1).put("say", "Walk out now, it is a scary fire."); assertRejected(a, "prohibited practice copy");
        ObjectNode b = together(); turn(b, 1).put("grownUp", "Nobody should be hurt."); assertRejected(b, "prohibited practice copy");
        ObjectNode c = together(); turn(c, 4).put("ask", "Who would be alone?"); assertRejected(c, "prohibited practice copy");
        ObjectNode d = together(); activity(d).put("remember", "Your score is what matters."); assertRejected(d, "prohibited practice copy");
        ObjectNode e = together(); activity(e).put("talkAboutIt", "What if someone was injured?"); assertRejected(e, "prohibited practice copy");
        ObjectNode f = together(); ((ArrayNode) activity(f).get("followUps")).add("Save your family"); // child-responsibility framing
        assertRejected(f, "prohibited practice copy");
        ObjectNode g = together(); ((ArrayNode) activity(g).get("followUps")).add("Practice so nobody panic"); assertRejected(g, "prohibited practice copy");
    }

    @Test
    void togetherActivityCarriesNoPilesOrItems() {
        ObjectNode root = together();
        activity(root).putArray("items").addObject().put("key", "a").put("label", "A");
        assertRejected(root, "no piles or items");
    }

    @Test
    void itemSortRejectsTogetherFields() {
        ObjectNode root = family("kit-test", 1);
        activity(root).put("remember", "x");
        assertRejected(root, "Together fields belong only to a TOGETHER activity");
    }

    @Test
    void itemSortStillValidatesUnchanged() {
        assertThat(errors(family("kit-test", 1))).isEmpty();
    }

    // -- DTO mapping ---------------------------------------------------------

    private FamilyPracticeService serviceFor(Version v, PracticeTogetherSessionRepo sessions) {
        PracticeAvailabilityService availability = mock(PracticeAvailabilityService.class);
        when(availability.checkStart(v.key())).thenReturn(new StartCheck(new Entry(v, true), null));
        PracticeSuppressionService suppression = mock(PracticeSuppressionService.class);
        when(suppression.check(anyString(), anyString())).thenReturn(Optional.empty());
        GroupRepo groups = mock(GroupRepo.class);
        Group g = new Group();
        g.setGroupId("hh");
        g.setGroupType("Household");
        when(groups.findByGroupId("hh")).thenReturn(Optional.of(g));
        FamilyPracticeCompletionRepo completions = mock(FamilyPracticeCompletionRepo.class);
        when(completions.findByHouseholdIdAndActivityKeyAndContentVersion(any(), any(), any(Integer.class)))
                .thenReturn(Optional.empty());
        return new FamilyPracticeService(availability, suppression, completions, groups,
                mock(HouseholdAccessService.class), sessions,
                Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void togetherDetailMapsRolesTurnsAndLabelsFromTheEnum() {
        Version v = version(together());
        ActivityDetailDto d = serviceFor(v, mock(PracticeTogetherSessionRepo.class)).detail(v.key(), "a@x.com", "hh");
        FamilyPracticeDtos.ActivityDto a = d.activity();
        assertThat(a.type()).isEqualTo("TOGETHER");
        assertThat(a.recommendedStyle()).isEqualTo("TAKE_A_TURN");
        assertThat(a.roles()).extracting(FamilyPracticeDtos.RoleDto::key, FamilyPracticeDtos.RoleDto::label,
                        FamilyPracticeDtos.RoleDto::adultOnly)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("CHECKER", "Checker", false),
                        org.assertj.core.groups.Tuple.tuple("HEAD_COUNTER", "Head counter", false));
        assertThat(a.turns()).hasSize(6);
        assertThat(a.turns().get(0).roleLabel()).isEqualTo("Adult");
        assertThat(a.turns().get(1).roleLabel()).isEqualTo("Everyone");
        assertThat(a.turns().get(3).roleLabel()).isEqualTo("Head counter");
        assertThat(a.turns().get(4).roleLabel()).isEqualTo("Anyone");
        assertThat(a.turns().get(4).ask()).isNotBlank();
        assertThat(a.remember()).isNotBlank();
        assertThat(a.talkAboutIt()).isNotBlank();
        assertThat(a.followUps()).hasSize(2);
        assertThat(a.piles()).isEmpty();
        assertThat(a.items()).isEmpty();
        assertThat(d.activeSession()).isNull();
    }

    @Test
    void itemSortDetailKeepsTogetherFieldsEmpty() {
        Version v = version(family("kit-builder", 1));
        ActivityDetailDto d = serviceFor(v, mock(PracticeTogetherSessionRepo.class)).detail(v.key(), "a@x.com", "hh");
        FamilyPracticeDtos.ActivityDto a = d.activity();
        assertThat(a.type()).isEqualTo("ITEM_SORT");
        assertThat(a.items()).hasSize(1);
        assertThat(a.recommendedStyle()).isNull();
        assertThat(a.roles()).isEmpty();
        assertThat(a.turns()).isEmpty();
        assertThat(a.remember()).isNull();
        assertThat(a.talkAboutIt()).isNull();
        assertThat(a.followUps()).isEmpty();
        assertThat(d.activeSession()).isNull();
    }

    @Test
    void theWireNamesAreTheContractsNames() {
        assertThat(java.util.Arrays.stream(FamilyPracticeDtos.RoleDto.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("key", "label", "adultOnly");
        assertThat(java.util.Arrays.stream(FamilyPracticeDtos.TurnDto.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("rhythm", "role", "roleLabel", "say", "grownUp", "ask");
        assertThat(java.util.Arrays.stream(FamilyPracticeDtos.ActiveSessionDto.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("sessionId", "style", "turnIndex", "expiresAt");
        assertThat(java.util.Arrays.stream(TogetherSessionService.SessionDto.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("sessionId", "activityKey", "contentVersion", "style", "turnIndex", "expiresAt");
    }

    @Test
    void theRoleEnumOwnsWhoIsAdultOnly() {
        assertThat(java.util.Arrays.stream(TogetherRole.values()).filter(TogetherRole::adultOnly))
                .containsExactlyInAnyOrder(TogetherRole.UTILITIES, TogetherRole.MEDICAL, TogetherRole.DRIVER);
        assertThat(TogetherRole.HEAD_COUNTER.label()).isEqualTo("Head counter");
        assertThat(TogetherRole.PET_BUDDY.adultOnly()).isFalse();
    }
}
