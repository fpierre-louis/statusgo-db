package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.sitprep.sitprepapi.practice.PracticeFixtures.approve;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.family;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.node;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.scenario;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.version;
import static org.assertj.core.api.Assertions.assertThat;

class PracticeCatalogTest {

    @Test
    void newestPublishedVersionIsTheOneThatStarts() {
        PracticeCatalog c = PracticeCatalog.of(List.of(
                version(approve(scenario("k", 1), PublishState.PUBLISHED)),
                version(approve(scenario("k", 2), PublishState.PUBLISHED)),
                version(scenario("k", 3)))); // a newer DRAFT never starts in production
        assertThat(c.latestStartable("k", false)).map(Version::version).contains(2);
        assertThat(c.latestStartable("k", true)).map(Version::version).contains(3);
    }

    @Test
    void retiredNeverStartsEvenInPreview() {
        PracticeCatalog c = PracticeCatalog.of(List.of(version(approve(scenario("k", 1), PublishState.RETIRED))));
        assertThat(c.latestStartable("k", false)).isEmpty();
        assertThat(c.latestStartable("k", true)).isEmpty();
        assertThat(c.exact("k", 1)).isPresent(); // still there for runs that pinned it
    }

    @Test
    void invalidContentIsExcludedAndReported() {
        ObjectNode tampered = approve(scenario("bad", 1), PublishState.PUBLISHED);
        node(tampered, 0).put("prompt", "Edited after review");
        PracticeCatalog c = PracticeCatalog.of(List.of(
                version(tampered),
                version(approve(scenario("good", 1), PublishState.PUBLISHED))));

        assertThat(c.versions("bad")).isEmpty();
        assertThat(c.latestStartable("bad", true)).isEmpty();
        assertThat(c.rejected()).singleElement()
                .satisfies(r -> assertThat(r.problems()).anySatisfy(p -> assertThat(p).contains("changed since")));
        assertThat(c.latestStartable("good", false)).isPresent();
    }

    @Test
    void duplicateKeyAndVersionRejectsBoth() {
        ObjectNode a = approve(scenario("k", 1), PublishState.PUBLISHED);
        ObjectNode b = scenario("k", 1);
        node(b, 0).put("title", "A different text claiming the same identity");
        PracticeCatalog c = PracticeCatalog.of(List.of(version(a), version(b)));
        assertThat(c.versions("k")).isEmpty();
        assertThat(c.rejected()).hasSize(2);
    }

    @Test
    void keysAreListedPerKind() {
        PracticeCatalog c = PracticeCatalog.of(List.of(
                version(scenario("scn-b", 1)), version(scenario("scn-a", 1)), version(family("fam", 1))));
        assertThat(c.keys(PracticeKind.ADULT_SCENARIO)).containsExactly("scn-a", "scn-b");
        assertThat(c.keys(PracticeKind.FAMILY_ACTIVITY)).containsExactly("fam");
    }

    @Test
    void loadsFilesFromAClasspathLocation() {
        PracticeCatalog c = new PracticeCatalog("classpath*:practice-fixtures/**/*.json");
        assertThat(c.versions("fixture-scenario")).extracting(Version::version).containsExactly(1);
        assertThat(c.rejected()).singleElement()
                .satisfies(r -> assertThat(r.resourcePath()).contains("broken-fixture"));
    }

    @Test
    void anEmptyLocationIsAnEmptyCatalog() {
        PracticeCatalog c = new PracticeCatalog("classpath*:practice-nothing-here/**/*.json");
        assertThat(c.all()).isEmpty();
        assertThat(c.rejected()).isEmpty();
    }
}
