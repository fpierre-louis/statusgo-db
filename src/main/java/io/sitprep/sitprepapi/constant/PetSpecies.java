package io.sitprep.sitprepapi.constant;

import java.util.Locale;

/**
 * Which pet column of {@code Demographic} a named pet is counted in: dogs,
 * cats, or {@code pets} (every other species, and a pet with no species).
 * Same mapping the FE's add-a-pet handler wrote with, and the V100 repair.
 */
public enum PetSpecies {
    DOG, CAT, OTHER;

    public static PetSpecies of(String species) {
        if (species == null) return OTHER;
        String v = species.trim().toLowerCase(Locale.ROOT);
        if (v.equals("dog")) return DOG;
        if (v.equals("cat")) return CAT;
        return OTHER;
    }
}
