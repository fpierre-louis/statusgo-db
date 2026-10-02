package io.sitprep.sitprepapi.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompassTest {

    @Test
    void cardinalAndIntercardinalBearings() {
        assertThat(Compass.point(0)).isEqualTo("N");
        assertThat(Compass.point(90)).isEqualTo("E");
        assertThat(Compass.point(180)).isEqualTo("S");
        assertThat(Compass.point(247)).isEqualTo("WSW");
        assertThat(Compass.point(315)).isEqualTo("NW");
    }

    @Test
    void boundariesRoundUpAndWrapPastNorth() {
        assertThat(Compass.point(11)).isEqualTo("N");
        assertThat(Compass.point(12)).isEqualTo("NNE");
        assertThat(Compass.point(349)).isEqualTo("N");
        assertThat(Compass.point(360)).isEqualTo("N");
        assertThat(Compass.point(-90)).isEqualTo("W");
    }

    @Test
    void noReadingIsNull() {
        assertThat(Compass.point(null)).isNull();
    }
}
