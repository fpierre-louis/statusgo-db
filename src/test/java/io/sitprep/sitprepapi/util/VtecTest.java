package io.sitprep.sitprepapi.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** NWS P-VTEC parsing (S2 contract §2). Pure: no clock, no network. */
class VtecTest {

    @Test
    void parsesTheContractExample() {
        Vtec v = Vtec.parse("/O.CON.KSLC.FF.W.0042.261013T1800Z-261014T0000Z/").orElseThrow();

        assertThat(v.action()).isEqualTo("CON");
        assertThat(v.office()).isEqualTo("KSLC");
        assertThat(v.phenomena()).isEqualTo("FF");
        assertThat(v.significance()).isEqualTo("W");
        assertThat(v.etn()).isEqualTo("0042");
        assertThat(v.beginsAt()).isEqualTo("2026-10-13T18:00:00Z");
        assertThat(v.endsAt()).isEqualTo("2026-10-14T00:00:00Z");
        assertThat(v.year()).isEqualTo(2026);
        assertThat(v.eventKey()).isEqualTo("KSLC.FF.W.0042.2026");
    }

    @Test
    void parsesEachLifecycleAction() {
        for (String action : List.of("NEW", "CON", "EXT", "EXA", "EXB", "UPG", "CAN", "EXP", "COR", "ROU")) {
            Optional<Vtec> v = Vtec.parse("/O." + action + ".KBOU.WS.A.0007.261101T0600Z-261102T0000Z/");
            assertThat(v).as(action).isPresent();
            assertThat(v.get().action()).isEqualTo(action);
            assertThat(v.get().eventKey()).isEqualTo("KBOU.WS.A.0007.2026");
        }
    }

    @Test
    void anUpgradeAndItsReplacementAreDifferentEvents() {
        // A Winter Storm Watch upgraded to a Warning: the UPG line keeps the watch's
        // own key, and the warning is a NEW event with its own.
        Vtec upg = Vtec.parse("/O.UPG.KBOU.WS.A.0007.261101T0600Z-261102T0000Z/").orElseThrow();
        Vtec nw = Vtec.parse("/O.NEW.KBOU.WS.W.0012.261101T0600Z-261102T0000Z/").orElseThrow();
        assertThat(upg.eventKey()).isEqualTo("KBOU.WS.A.0007.2026");
        assertThat(nw.eventKey()).isEqualTo("KBOU.WS.W.0012.2026");
    }

    @Test
    void unspecifiedTimesAreNull_andTheYearFallsBackToSent() {
        // How a CON for an event already in effect codes its begin time.
        Vtec v = Vtec.parse("/O.CON.KGRR.SC.Y.0060.000000T0000Z-260824T0300Z/",
                "2026-08-22T14:59:00-04:00").orElseThrow();
        assertThat(v.beginsAt()).isNull();
        assertThat(v.endsAt()).isEqualTo("2026-08-24T03:00:00Z");
        assertThat(v.year()).isEqualTo(2026);
        assertThat(v.eventKey()).isEqualTo("KGRR.SC.Y.0060.2026");
    }

    @Test
    void withNoBeginAndNoSentTheYearComesFromTheEnd() {
        Vtec v = Vtec.parse("/O.EXP.KGRR.SC.Y.0060.000000T0000Z-270101T0300Z/").orElseThrow();
        assertThat(v.year()).isEqualTo(2027);
    }

    @Test
    void untilFurtherNoticeLeavesTheEndNull_andTheKeyNeedsSomeYear() {
        Vtec noYear = Vtec.parse("/O.CON.KXXX.FL.W.0001.000000T0000Z-000000T0000Z/").orElseThrow();
        assertThat(noYear.endsAt()).isNull();
        assertThat(noYear.year()).isNull();
        assertThat(noYear.eventKey()).as("an ETN without its year is not unique").isNull();

        Vtec withSent = Vtec.parse("/O.CON.KXXX.FL.W.0001.000000T0000Z-000000T0000Z/",
                "2026-10-13T12:00:00Z").orElseThrow();
        assertThat(withSent.eventKey()).isEqualTo("KXXX.FL.W.0001.2026");
    }

    @Test
    void slashesAndSurroundingWhitespaceAreOptional() {
        assertThat(Vtec.parse("  O.CAN.KSLC.FF.W.0042.261013T1800Z-261014T0000Z  ")).isPresent();
    }

    @Test
    void malformedStringsAreIgnored_neverThrown() {
        for (String bad : new String[] {
                null, "", "garbage",
                "/O.CON.KSLC.FF.W.42.261013T1800Z-261014T0000Z/",      // short ETN
                "/O.XYZ.KSLC.FF.W.0042.261013T1800Z-261014T0000Z/",    // unknown action
                "/O.CON.KSL.FF.W.0042.261013T1800Z-261014T0000Z/",     // 3-letter office
                "/O.CON.KSLC.FF.W.0042.261313T1800Z-261014T0000Z/",    // month 13
                "/O.CON.KSLC.FF.W.0042.260231T1800Z-260301T0000Z/",    // 31 Feb (not clamped to 28)
                "/O.CON.KSLC.FF.W.0042.250229T1800Z-250301T0000Z/",    // 29 Feb in a non-leap year
                "/O.CON.KSLC.FF.W.0042.261013T2460Z-261014T0000Z/",    // minute 60
                "/O.CON.KSLC.FF.W.0042.261013T1800Z/",                 // no end
                "/00000.0.ER.000000T0000Z.000000T0000Z.000000T0000Z.OO/" // H-VTEC shape
        }) {
            assertThat(Vtec.parse(bad, "2026-10-13T00:00:00Z")).as(String.valueOf(bad)).isEmpty();
        }
    }

    @Test
    void aRealLeapDayStillParses() {
        // The strict resolver rejects 31 Feb; it must not reject 29 Feb in a leap year.
        assertThat(Vtec.parse("/O.NEW.KSLC.FF.W.0003.280229T1800Z-280301T0000Z/"))
                .get().extracting(Vtec::beginsAt, Vtec::eventKey)
                .containsExactly("2028-02-29T18:00:00Z", "KSLC.FF.W.0003.2028");
    }

    @Test
    void nonOperationalClassesAreSkipped() {
        // A Test-class VTEC must never become a verified lifecycle edge.
        assertThat(Vtec.parse("/T.CON.KSLC.FF.W.0042.261013T1800Z-261014T0000Z/")).isEmpty();
        assertThat(Vtec.parse("/E.CON.KSLC.FF.W.0042.261013T1800Z-261014T0000Z/")).isEmpty();
    }

    @Test
    void parseAllKeepsGoodEntriesAndDropsBadOnes_eventKeysAreDistinct() {
        List<Vtec> all = Vtec.parseAll(List.of(
                "/O.EXT.KSLC.FF.W.0042.261013T1800Z-261014T0300Z/",
                "not vtec",
                "/O.CON.KSLC.FF.W.0042.261013T1800Z-261014T0300Z/"), null);
        assertThat(all).extracting(Vtec::action).containsExactly("EXT", "CON");
        assertThat(Vtec.eventKeysOf(all)).containsExactly("KSLC.FF.W.0042.2026");
        assertThat(Vtec.parseAll(null, null)).isEmpty();
        assertThat(Vtec.eventKeysOf(null)).isEmpty();
    }
}
