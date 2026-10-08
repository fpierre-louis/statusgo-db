package io.sitprep.sitprepapi.practice;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build gate for real content: every file under
 * {@code src/main/resources/practice/content} must load and validate. At
 * runtime an invalid file is only excluded and logged; here it fails the
 * build, which is where it should be caught.
 */
class PracticeShippedContentTest {

    @Test
    void everyShippedVersionValidates() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        assertThat(catalog.rejected()).as("rejected practice content").isEmpty();
    }

    @Test
    void aKeyNeverChangesKindAcrossVersions() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Map<String, PracticeKind> kinds = new HashMap<>();
        catalog.all().forEach(v -> {
            PracticeKind prior = kinds.putIfAbsent(v.key(), v.kind());
            assertThat(prior == null || prior == v.kind())
                    .as("key %s changes kind between versions", v.key()).isTrue();
        });
    }
}
