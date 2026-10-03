package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The one geocoding client (open-items 3.4): Photon parsing, and the shared cache. */
class GeocodeClientTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void photonKeepsUsHitsWithAReadableLabel() throws Exception {
        String json = """
            {"features":[
              {"geometry":{"coordinates":[-111.84,40.49]},
               "properties":{"countrycode":"US","housenumber":"1450","street":"N Traverse Ridge Rd",
                             "city":"Draper","state":"Utah","postcode":"84020"}},
              {"geometry":{"coordinates":[2.35,48.85]},
               "properties":{"countrycode":"FR","name":"Paris","state":"Île-de-France"}},
              {"geometry":{"coordinates":[-111.85,40.39]},
               "properties":{"countrycode":"US","name":"Lehi Rec Center","street":"Center St","city":"Lehi","state":"Utah"}}
            ]}""";
        List<GeocodeClient.Suggestion> out = GeocodeClient.parsePhoton(om.readTree(json), 5);
        assertEquals(2, out.size());
        assertEquals("1450 N Traverse Ridge Rd, Draper, Utah 84020", out.get(0).label());
        assertEquals(40.49, out.get(0).lat(), 1e-9);
        assertEquals(-111.84, out.get(0).lng(), 1e-9);
        assertEquals("Lehi Rec Center, Center St, Lehi, Utah", out.get(1).label());
    }

    @Test
    void cacheServesRepeatsAndKeepsAMissBrieflyNotForever() {
        GeocodeClient c = new GeocodeClient(om);
        AtomicInteger calls = new AtomicInteger();
        assertEquals("x", c.cached("k", 60_000, 60_000, () -> { calls.incrementAndGet(); return "x"; }));
        assertEquals("x", c.cached("k", 60_000, 60_000, () -> { calls.incrementAndGet(); return "y"; }));
        assertEquals(1, calls.get());
        // A miss (null) with a zero miss-TTL is asked again next time.
        assertNull(c.cached("m", 60_000, 0, () -> null));
        assertEquals("z", c.cached("m", 60_000, 0, () -> "z"));
    }

    @Test
    void cacheIsBounded() {
        GeocodeClient c = new GeocodeClient(om);
        for (int i = 0; i < GeocodeClient.CACHE_MAX + 50; i++) {
            final int n = i;
            c.cached("k" + n, 60_000, 60_000, () -> n);
        }
        assertEquals(GeocodeClient.CACHE_MAX, c.cacheSize());
    }
}
