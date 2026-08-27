package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CAP {@code references} — the supersession edge.
 *
 * <p><b>The measurement these tests encode.</b> Live NWS feed, 2026-08-26, 252
 * active alerts: 73 carry {@code references} (29%) and all 73 are
 * {@code messageType: Update} — but <b>0 of the 99 referenced identifiers
 * resolved inside the same response.</b></p>
 *
 * <p>That zero is structural, not a gap: {@code /alerts/active} returns only
 * active alerts, and a replaced alert is by definition no longer one. So the
 * FORWARD edge ("this replaces an earlier alert") is real today and the REVERSE
 * edge ("replaced by X") is almost always unresolvable — which is why the index
 * returns an empty map rather than inventing a title, and why the surface must
 * be able to render nothing.</p>
 */
class AlertSupersessionTest {

    private static NormalizedAlert original() {
        return TestAlerts.nws("Flood Warning").id("urn:oid:OLD").build();
    }

    private static NormalizedAlert update() {
        return TestAlerts.nws("Flash Flood Warning")
                .id("urn:oid:NEW")
                .messageType("Update")
                .references(List.of("urn:oid:OLD"))
                .build();
    }

    @Test
    @DisplayName("the forward edge survives ingest: an Update names what it replaces")
    void forwardEdgeIsCarried() {
        assertThat(update().references()).containsExactly("urn:oid:OLD");
        // And the original names nothing — the edge has one direction.
        assertThat(original().references()).isEmpty();
    }

    @Test
    @DisplayName("the reverse edge resolves only when BOTH ends are in the snapshot")
    void reverseEdgeNeedsBothEnds() {
        Map<String, NormalizedAlert> index =
                AlertDerivations.supersessionIndex(List.of(original(), update()));
        assertThat(index).containsOnlyKeys("urn:oid:OLD");
        assertThat(index.get("urn:oid:OLD").id()).isEqualTo("urn:oid:NEW");
        assertThat(AlertDerivations.supersededByTitle(index.get("urn:oid:OLD")))
                .isEqualTo("Flash Flood Warning");
    }

    @Test
    @DisplayName("the live case: the replaced alert is gone, so nothing is claimed")
    void unresolvableReferenceClaimsNothing() {
        // This is what /alerts/active actually returns — the Update alone. A
        // link to an id we cannot resolve would go nowhere, so the index simply
        // does not contain it.
        Map<String, NormalizedAlert> index =
                AlertDerivations.supersessionIndex(List.of(update()));
        assertThat(index).isEmpty();
        assertThat(AlertDerivations.supersededByTitle(null)).isNull();
    }

    @Test
    @DisplayName("an Update is `updated`, and lifecycle never invents a supersession")
    void lifecycleAgreesWithTheEdge() {
        // messageType: Update is the 29% that carry references. `superseded` is
        // reserved for an alert that says so itself — Cancel or AllClear —
        // because the arrival of a replacement is not visible from this end.
        assertThat(AlertDerivations.lifecycleState(update(), null))
                .isEqualTo(AlertDerivations.LIFECYCLE_UPDATED);

        NormalizedAlert cancelled = TestAlerts.nws("Flood Warning")
                .id("urn:oid:OLD").messageType("Cancel").build();
        assertThat(AlertDerivations.lifecycleState(cancelled, null))
                .isEqualTo(AlertDerivations.LIFECYCLE_SUPERSEDED);
    }

    @Test
    @DisplayName("sources with no CAP vocabulary carry an empty edge, never null")
    void nonCapSourcesAreEmptyNotNull() {
        assertThat(TestAlerts.usgs("M5.8 - 10km NE of Somewhere").build().references())
                .isNotNull().isEmpty();
        assertThat(TestAlerts.fema("Severe Storms — DR-1234").build().references())
                .isNotNull().isEmpty();
    }
}
