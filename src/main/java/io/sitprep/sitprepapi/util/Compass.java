package io.sitprep.sitprepapi.util;

/**
 * Wind direction in degrees to a 16-point compass word ("WSW").
 *
 * <p>Degrees are where the wind blows FROM, the meteorological convention
 * NWS uses, so 247° reads "WSW winds". Each point covers
 * 22.5°, centred on its bearing; a value exactly on a boundary (11.25°) rounds
 * up to the next point.</p>
 */
public final class Compass {

    private static final String[] POINTS = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    };

    private Compass() {}

    /** The 16-point word for {@code degrees}, or null when there is no reading. */
    public static String point(Integer degrees) {
        if (degrees == null) return null;
        int d = ((degrees % 360) + 360) % 360;
        return POINTS[(int) (Math.round(d / 22.5) % 16)];
    }
}
