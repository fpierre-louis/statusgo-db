package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeContent.Media;
import io.sitprep.sitprepapi.practice.PracticeContent.MediaKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PracticeMediaManifestTest {

    @Test
    void shippedManifestIsValidAndResolvesOnlyAllowListedHashes() {
        PracticeMediaManifest manifest = PracticeMediaManifest.getDefault();
        assertThat(manifest.problems()).isEmpty();
        String sha = "sha256:a366d5d2daa3cae65a0de66c6096cb97cfec998b9beef702ee3e1ac94481951c";
        assertThat(manifest.find(sha)).isPresent().get().satisfies(asset -> {
            assertThat(asset.kind()).isEqualTo(MediaKind.STILL);
            assertThat(asset.widths()).containsExactly(640, 960, 1280);
            assertThat(asset.stem()).contains("a366d5d2");
        });

        var resolved = manifest.resolve(new Media("slot", MediaKind.STILL, sha, null, null, "Visible scene."));
        assertThat(resolved.url()).endsWith("-640.jpg");
        assertThat(resolved.srcSet()).contains("-640.jpg 640w", "-1280.jpg 1280w");
        assertThat(resolved.alt()).isEqualTo("Visible scene.");
        assertThat(manifest.find("sha256:" + "0".repeat(64))).isEmpty();
    }

    @Test
    void anIncompleteOrDuplicatedManifestReportsProblems() {
        var a = new PracticeMediaManifest.Asset("sha256:" + "a".repeat(64), PracticeContent.MediaStyle.REALISM,
                MediaKind.STILL, "/images/x", java.util.List.of(640), 640, 360, "center", 10L, null);
        var noWidths = new PracticeMediaManifest.Asset("sha256:" + "b".repeat(64), PracticeContent.MediaStyle.REALISM,
                MediaKind.STILL, "/images/y", java.util.List.of(), 640, 360, "center", 10L, null);
        var m = new PracticeMediaManifest(new PracticeMediaManifest.File(1, java.util.List.of(a, a, noWidths)));
        assertThat(m.problems()).anyMatch(p -> p.contains("duplicate")).anyMatch(p -> p.contains("responsive widths"));
        assertThat(new PracticeMediaManifest(new PracticeMediaManifest.File(2, java.util.List.of())).problems())
                .anyMatch(p -> p.contains("schema must be 1"));
    }
}
