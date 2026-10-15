package io.sitprep.sitprepapi.util;

import io.sitprep.sitprepapi.util.AlertIdentity.Resolved;
import io.sitprep.sitprepapi.util.AlertIdentity.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One resolver for every spelling of an alert's name (S2 contract §1).
 *
 * <p>The forms in use: {@code SOURCE:id} (the FE {@code alertRecordKey}, the
 * new dedupe key), {@code SOURCE-id} ({@code AlertPost.alertId}, notification
 * {@code referenceId}) and a bare id next to a separate source field. A bare id
 * with nothing to say which source it is from is ambiguous, never guessed.</p>
 */
class AlertIdentityTest {

    private static final String NWS_ID = "urn:oid:2.49.0.1.840.0.d23f346b0ae1a954eba9c52762f3ddbe1b913873.001.1";

    // ── dedupeKey / legacy key ──────────────────────────────────────────

    @Test
    void dedupeKeyUpperCasesTheSourceAndKeepsTheIdVerbatim() {
        assertThat(AlertIdentity.dedupeKey("nws", NWS_ID)).isEqualTo("NWS:" + NWS_ID);
        assertThat(AlertIdentity.dedupeKey("USGS", "us7000AbC")).isEqualTo("USGS:us7000AbC");
        assertThat(AlertIdentity.dedupeKey("Fema", "DR-4932-WV")).isEqualTo("FEMA:DR-4932-WV");
    }

    @Test
    void dedupeKeyIsNullForAnUnknownSourceOrBlankId() {
        assertThat(AlertIdentity.dedupeKey("NOAA", "x")).isNull();
        assertThat(AlertIdentity.dedupeKey(null, "x")).isNull();
        assertThat(AlertIdentity.dedupeKey("NWS", " ")).isNull();
        assertThat(AlertIdentity.dedupeKey("NWS", null)).isNull();
    }

    @Test
    void legacyDispatchKeyIsTheDashFormDispatchPersists() {
        // AlertDispatchService writes `a.source() + "-" + a.id()`.
        assertThat(AlertIdentity.toLegacyDispatchKey("NWS", NWS_ID)).isEqualTo("NWS-" + NWS_ID);
        assertThat(AlertIdentity.toLegacyDispatchKey("fema", "DR-4932-WV")).isEqualTo("FEMA-DR-4932-WV");
        assertThat(AlertIdentity.toLegacyDispatchKey("NOAA", "x")).isNull();
    }

    // ── resolve: the three forms ────────────────────────────────────────

    @Test
    void resolvesColonForm_evenThoughNwsIdsThemselvesContainColons() {
        Resolved r = AlertIdentity.resolve("NWS:" + NWS_ID, null);
        assertThat(r.status()).isEqualTo(Status.RESOLVED);
        assertThat(r.source()).isEqualTo("NWS");
        assertThat(r.upstreamId()).isEqualTo(NWS_ID);
        assertThat(r.dedupeKey()).isEqualTo("NWS:" + NWS_ID);
        assertThat(r.legacyDispatchKey()).isEqualTo("NWS-" + NWS_ID);
    }

    @Test
    void resolvesDashFormForEachSource() {
        assertThat(AlertIdentity.resolve("NWS-" + NWS_ID, null))
                .isEqualTo(new Resolved(Status.RESOLVED, "NWS", NWS_ID));
        assertThat(AlertIdentity.resolve("USGS-us7000abc", null))
                .isEqualTo(new Resolved(Status.RESOLVED, "USGS", "us7000abc"));
        // FEMA ids contain dashes themselves; only the first prefix is the source.
        assertThat(AlertIdentity.resolve("FEMA-DR-4932-WV", null))
                .isEqualTo(new Resolved(Status.RESOLVED, "FEMA", "DR-4932-WV"));
    }

    @Test
    void prefixesMatchCaseInsensitively_idCaseIsKept() {
        assertThat(AlertIdentity.resolve("usgs:us7000AbC", null))
                .isEqualTo(new Resolved(Status.RESOLVED, "USGS", "us7000AbC"));
        assertThat(AlertIdentity.resolve("fema-DR-1-OK", null))
                .isEqualTo(new Resolved(Status.RESOLVED, "FEMA", "DR-1-OK"));
    }

    @Test
    void resolvesBareIdWithAHint() {
        assertThat(AlertIdentity.resolve(NWS_ID, "nws"))
                .isEqualTo(new Resolved(Status.RESOLVED, "NWS", NWS_ID));
        assertThat(AlertIdentity.resolve("us7000abc", "USGS"))
                .isEqualTo(new Resolved(Status.RESOLVED, "USGS", "us7000abc"));
        assertThat(AlertIdentity.resolve("DR-4932-WV", "FEMA"))
                .isEqualTo(new Resolved(Status.RESOLVED, "FEMA", "DR-4932-WV"));
    }

    @Test
    void outerWhitespaceIsTrimmed() {
        assertThat(AlertIdentity.resolve("  USGS:us1  ", null).upstreamId()).isEqualTo("us1");
    }

    // ── resolve: ambiguity and invalid input ────────────────────────────

    @Test
    void bareIdWithoutAHintIsAmbiguous_neverGuessed() {
        // Including an NWS urn, whose "urn" prefix must not be read as a source.
        for (String bare : new String[] { NWS_ID, "us7000abc", "DR-4932-WV" }) {
            Resolved r = AlertIdentity.resolve(bare, null);
            assertThat(r.status()).as(bare).isEqualTo(Status.AMBIGUOUS);
            assertThat(r.source()).isNull();
            assertThat(r.upstreamId()).isNull();
            assertThat(r.dedupeKey()).isNull();
            assertThat(r.legacyDispatchKey()).isNull();
        }
        assertThat(AlertIdentity.resolve("us7000abc", "  ").status()).isEqualTo(Status.AMBIGUOUS);
    }

    @Test
    void aPrefixThatContradictsTheHintIsAmbiguous() {
        assertThat(AlertIdentity.resolve("USGS-us7000abc", "FEMA").status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(AlertIdentity.resolve("NWS:" + NWS_ID, "USGS").status()).isEqualTo(Status.AMBIGUOUS);
        // ...and an agreeing hint is fine.
        assertThat(AlertIdentity.resolve("USGS-us7000abc", "usgs").status()).isEqualTo(Status.RESOLVED);
    }

    @Test
    void blankEmptyIdOrUnknownHintIsInvalid() {
        assertThat(AlertIdentity.resolve(null, "NWS").status()).isEqualTo(Status.INVALID);
        assertThat(AlertIdentity.resolve("   ", "NWS").status()).isEqualTo(Status.INVALID);
        assertThat(AlertIdentity.resolve("NWS:", null).status()).isEqualTo(Status.INVALID);
        assertThat(AlertIdentity.resolve("FEMA-", null).status()).isEqualTo(Status.INVALID);
        assertThat(AlertIdentity.resolve("us7000abc", "NOAA").status()).isEqualTo(Status.INVALID);
    }

    @Test
    void anUnknownColonPrefixIsJustPartOfABareId() {
        assertThat(AlertIdentity.resolve("NOAA:abc", "NWS"))
                .isEqualTo(new Resolved(Status.RESOLVED, "NWS", "NOAA:abc"));
        assertThat(AlertIdentity.resolve("NOAA:abc", null).status()).isEqualTo(Status.AMBIGUOUS);
    }

    @Test
    void everyFormRoundTripsToTheSameDedupeKey() {
        String expected = "FEMA:DR-4932-WV";
        assertThat(AlertIdentity.resolve("FEMA:DR-4932-WV", null).dedupeKey()).isEqualTo(expected);
        assertThat(AlertIdentity.resolve("FEMA-DR-4932-WV", null).dedupeKey()).isEqualTo(expected);
        assertThat(AlertIdentity.resolve("DR-4932-WV", "fema").dedupeKey()).isEqualTo(expected);
    }

    /** The frontend's alertRecordKey trims the id; both sides must mint one key. */
    @Test
    void surroundingWhitespaceOnTheIdIsTrimmed_likeTheFrontend() {
        assertThat(AlertIdentity.dedupeKey("usgs", " us7000abc ")).isEqualTo("USGS:us7000abc");
        assertThat(AlertIdentity.toLegacyDispatchKey("FEMA", "DR-4932-WV\n")).isEqualTo("FEMA-DR-4932-WV");
        assertThat(AlertIdentity.resolve("NWS: urn:oid:2.49.0.1", null))
                .isEqualTo(new Resolved(Status.RESOLVED, "NWS", "urn:oid:2.49.0.1"));
        assertThat(AlertIdentity.resolve(" DR-4932-WV ", "FEMA").dedupeKey()).isEqualTo("FEMA:DR-4932-WV");
        // Inner content stays verbatim.
        assertThat(AlertIdentity.dedupeKey("NWS", "urn:oid:A b")).isEqualTo("NWS:urn:oid:A b");
    }
}
