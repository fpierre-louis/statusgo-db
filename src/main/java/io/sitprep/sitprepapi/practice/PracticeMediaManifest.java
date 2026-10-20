package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.practice.PracticeContent.Media;
import io.sitprep.sitprepapi.practice.PracticeContent.MediaKind;
import io.sitprep.sitprepapi.practice.PracticeContent.MediaStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The allow-list that turns a content hash into an immutable public asset.
 * Content never supplies a URL. A missing hash therefore fails validation
 * instead of becoming an arbitrary fetch from learner-authored data.
 */
public final class PracticeMediaManifest {

    private static final Logger log = LoggerFactory.getLogger(PracticeMediaManifest.class);
    private static final String RESOURCE = "/practice/media/manifest.json";
    private static final PracticeMediaManifest DEFAULT = load();

    public record File(Integer schema, List<Asset> assets) {}

    public record Asset(
            String sha256,
            MediaStyle style,
            MediaKind kind,
            String stem,
            List<Integer> widths,
            Integer width,
            Integer height,
            String focal,
            Long bytes,
            Integer durationMs) {}

    /** Client-ready, resolved media. URLs only originate in the manifest. */
    public record Resolved(
            String kind,
            String assetSha256,
            String url,
            String srcSet,
            Integer width,
            Integer height,
            String focal,
            Integer durationMs,
            String posterUrl,
            String alt) {}

    private final Map<String, Asset> byHash;
    private final List<String> problems;

    PracticeMediaManifest(File file) {
        Map<String, Asset> indexed = new LinkedHashMap<>();
        java.util.ArrayList<String> bad = new java.util.ArrayList<>();
        if (file == null || file.schema() == null || file.schema() != 1) {
            bad.add("practice media manifest schema must be 1");
        }
        for (Asset asset : file == null || file.assets() == null ? List.<Asset>of() : file.assets()) {
            if (asset == null || asset.sha256() == null) {
                bad.add("practice media manifest asset needs sha256");
                continue;
            }
            if (indexed.putIfAbsent(asset.sha256(), asset) != null) {
                bad.add("duplicate practice media hash " + asset.sha256());
            }
            if (asset.kind() == null || asset.style() == null || asset.width() == null || asset.height() == null
                    || asset.bytes() == null || asset.bytes() < 1 || asset.stem() == null || asset.stem().isBlank()) {
                bad.add("incomplete practice media asset " + asset.sha256());
            }
            if (asset.kind() == MediaKind.STILL && (asset.widths() == null || asset.widths().isEmpty())) {
                bad.add("still needs responsive widths " + asset.sha256());
            }
        }
        byHash = Map.copyOf(indexed);
        problems = List.copyOf(bad);
    }

    public static PracticeMediaManifest getDefault() { return DEFAULT; }

    public Optional<Asset> find(String sha256) { return Optional.ofNullable(byHash.get(sha256)); }

    public List<String> problems() { return problems; }

    public Resolved resolve(Media media) {
        if (media == null) return null;
        Asset asset = byHash.get(media.assetSha256());
        if (asset == null) return null;
        if (asset.kind() == MediaKind.STILL) {
            String srcSet = asset.widths().stream()
                    .map(w -> asset.stem() + "-" + w + ".jpg " + w + "w")
                    .collect(java.util.stream.Collectors.joining(", "));
            return new Resolved(asset.kind().name(), asset.sha256(),
                    asset.stem() + "-" + asset.widths().get(0) + ".jpg", srcSet,
                    asset.width(), asset.height(), asset.focal(), null, null, media.alt());
        }
        Asset poster = byHash.get(media.posterSha256());
        String posterUrl = poster == null ? null : poster.stem() + "-" + poster.widths().get(0) + ".jpg";
        return new Resolved(asset.kind().name(), asset.sha256(), asset.stem(), null,
                asset.width(), asset.height(), asset.focal(), media.durationMs(), posterUrl, media.alt());
    }

    private static PracticeMediaManifest load() {
        try (InputStream in = PracticeMediaManifest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            return new PracticeMediaManifest(new ObjectMapper().readValue(in, File.class));
        } catch (Exception e) {
            log.error("Practice media manifest unavailable", e);
            return new PracticeMediaManifest(new File(1, List.of()));
        }
    }
}
