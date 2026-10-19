package io.sitprep.sitprepapi.practice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class FamilyPracticeCompletionRepoTest {

    @Autowired FamilyPracticeCompletionRepo repo;
    @Autowired TestEntityManager em;

    @Test
    void householdCompletionIdentityAndTagsRoundTrip() {
        FamilyPracticeCompletion row = new FamilyPracticeCompletion();
        row.setHouseholdId("hh-1");
        row.setActivityKey("kit-builder");
        row.setContentVersion(1);
        row.setContentHash("sha256:abc");
        row.setCompletedAt(Instant.parse("2026-10-19T18:00:00Z"));
        row.setCompletedByEmail("adult@example.com");
        row.setDebriefTags(List.of("discussed_core_kit_items"));
        repo.save(row);
        em.flush();
        em.clear();

        FamilyPracticeCompletion back = repo
                .findByHouseholdIdAndActivityKeyAndContentVersion("hh-1", "kit-builder", 1).orElseThrow();
        assertThat(back.getCompletedByEmail()).isEqualTo("adult@example.com");
        assertThat(back.getDebriefTags()).containsExactly("discussed_core_kit_items");
        assertThat(repo.findByHouseholdIdOrderByCompletedAtDesc("hh-1")).hasSize(1);
    }
}
