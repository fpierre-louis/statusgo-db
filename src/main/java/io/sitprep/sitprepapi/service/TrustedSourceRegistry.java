package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.AlertIdentity;

import java.util.Map;

/**
 * Who an official alert is from, and what kind of record it is (S2 contract §2).
 *
 * <p><b>Static and code-reviewed on purpose.</b> "Official" is a claim SitPrep
 * makes on the issuer's behalf, so it comes only from the adapter that fetched
 * the alert — {@code NormalizedAlert.source}, which ingest sets itself — and
 * from this table. Nothing is read from payload names, sender strings or URLs:
 * those are the parts of a message anyone upstream of us could word however
 * they liked. Adding a source is a reviewed code change here, never data.</p>
 *
 * <p>{@code logoKey} stays null for every source until permission to show an
 * agency mark is documented (handoff §13).</p>
 */
public final class TrustedSourceRegistry {

    private TrustedSourceRegistry() {}

    public static final String RECORD_WARNING = "warning";
    public static final String RECORD_WATCH = "watch";
    public static final String RECORD_ADVISORY = "advisory";
    public static final String RECORD_STATEMENT = "statement";
    public static final String RECORD_INFORMATION = "information";
    public static final String RECORD_EARTHQUAKE_INFORMATION = "earthquake_information";
    public static final String RECORD_DISASTER_DECLARATION = "disaster_declaration";

    /**
     * The registry entry for one source.
     *
     * @param key      upper-cased source key, or null when none was given
     * @param name     display name; null for an unknown source
     * @param official true only for a registered adapter source
     * @param logoKey  always null today — see class doc
     */
    public record SourceIdentity(String key, String name, boolean official, String logoKey) {}

    private static final Map<String, String> NAMES = Map.of(
            "NWS", "National Weather Service",
            "USGS", "U.S. Geological Survey",
            "FEMA", "FEMA");

    /**
     * The identity for {@code source} (the adapter's own key). Unknown or
     * blank → {@code official:false} with no name: an unverified source is
     * never given one.
     */
    public static SourceIdentity of(String source) {
        String known = AlertIdentity.knownSource(source);
        if (known != null) return new SourceIdentity(known, NAMES.get(known), true, null);
        String key = source == null || source.isBlank()
                ? null
                : source.trim().toUpperCase(java.util.Locale.ROOT);
        return new SourceIdentity(key, null, false, null);
    }

    /**
     * The record type for one alert.
     *
     * <ul>
     *   <li>USGS → {@code earthquake_information};</li>
     *   <li>FEMA → {@code disaster_declaration};</li>
     *   <li>NWS (and anything else) → {@link AlertFeedService#tierOf}: the
     *       template's reviewed tier, else the product-name suffix of the
     *       controlled NWS event vocabulary — {@code warning}, {@code watch},
     *       {@code advisory}, {@code statement}, {@code information}. One
     *       helper, so the record type cannot disagree with the card's tier.</li>
     * </ul>
     */
    public static String recordTypeOf(NormalizedAlert a, DispatchTemplate template) {
        if (a == null) return null;
        String fixed = sourceRecordType(a.source());
        return fixed != null ? fixed : AlertFeedService.tierOf(a, template);
    }

    /**
     * The record type a SOURCE fixes on its own, without a template: USGS and
     * FEMA have exactly one each. Null for NWS, whose type needs the template
     * ({@link #recordTypeOf}).
     */
    public static String sourceRecordType(String source) {
        String known = AlertIdentity.knownSource(source);
        if ("USGS".equals(known)) return RECORD_EARTHQUAKE_INFORMATION;
        if ("FEMA".equals(known)) return RECORD_DISASTER_DECLARATION;
        return null;
    }
}
