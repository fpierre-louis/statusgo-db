package io.sitprep.sitprepapi.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SAME location codes, read in the vocabulary the zone matcher already speaks.
 *
 * <p>A SAME code is {@code PSSCCC}: P a county subdivision (0 = whole county),
 * SS the state FIPS, CCC the county FIPS. NWS county UGC codes are the same
 * county number under the state's postal code — {@code 006037} (Los Angeles
 * County) is {@code CAC037} — and {@code NwsZoneService.zoneCodesForPoint}
 * already returns the county UGC for a coordinate (Lehi, UT carries
 * {@code UTC049}). So a converted SAME code is matched by the existing zone
 * tier with no new lookup.</p>
 *
 * <p>Why this exists: some messages carry SAME and nothing else. A California
 * AMBER Alert on 2026-10-04 shipped four SAME counties, no UGC and no polygon,
 * and the matcher — which read only geometry and UGC — treated it as having no
 * location and showed it to every user in the country
 * (docs/epics/alert-targeting/EXEC-A1-same-codes.md in the FE repo).</p>
 */
public final class SameCodes {

    private SameCodes() {}

    /** Suffix marking a state-wide target ({@code SS000}): {@code "CA*"}. */
    public static final String STATEWIDE_SUFFIX = "*";

    /** State FIPS -> USPS code. States, DC and the inhabited territories; marine areas map to nothing. */
    private static final Map<String, String> STATE_BY_FIPS = Map.ofEntries(
            Map.entry("01", "AL"), Map.entry("02", "AK"), Map.entry("04", "AZ"), Map.entry("05", "AR"),
            Map.entry("06", "CA"), Map.entry("08", "CO"), Map.entry("09", "CT"), Map.entry("10", "DE"),
            Map.entry("11", "DC"), Map.entry("12", "FL"), Map.entry("13", "GA"), Map.entry("15", "HI"),
            Map.entry("16", "ID"), Map.entry("17", "IL"), Map.entry("18", "IN"), Map.entry("19", "IA"),
            Map.entry("20", "KS"), Map.entry("21", "KY"), Map.entry("22", "LA"), Map.entry("23", "ME"),
            Map.entry("24", "MD"), Map.entry("25", "MA"), Map.entry("26", "MI"), Map.entry("27", "MN"),
            Map.entry("28", "MS"), Map.entry("29", "MO"), Map.entry("30", "MT"), Map.entry("31", "NE"),
            Map.entry("32", "NV"), Map.entry("33", "NH"), Map.entry("34", "NJ"), Map.entry("35", "NM"),
            Map.entry("36", "NY"), Map.entry("37", "NC"), Map.entry("38", "ND"), Map.entry("39", "OH"),
            Map.entry("40", "OK"), Map.entry("41", "OR"), Map.entry("42", "PA"), Map.entry("44", "RI"),
            Map.entry("45", "SC"), Map.entry("46", "SD"), Map.entry("47", "TN"), Map.entry("48", "TX"),
            Map.entry("49", "UT"), Map.entry("50", "VT"), Map.entry("51", "VA"), Map.entry("53", "WA"),
            Map.entry("54", "WV"), Map.entry("55", "WI"), Map.entry("56", "WY"),
            Map.entry("60", "AS"), Map.entry("66", "GU"), Map.entry("69", "MP"), Map.entry("72", "PR"),
            Map.entry("78", "VI"));

    /**
     * {@code 006037} -> {@code CAC037}; {@code 006000} -> {@code CA*} (the whole
     * state); anything unparseable or offshore -> null.
     */
    public static String toUgc(String same) {
        if (same == null) return null;
        String s = same.trim();
        if (s.length() != 6 || !s.chars().allMatch(Character::isDigit)) return null;
        String state = STATE_BY_FIPS.get(s.substring(1, 3));
        if (state == null) return null;
        String county = s.substring(3);
        return "000".equals(county) ? state + STATEWIDE_SUFFIX : state + "C" + county;
    }

    /** {@link #toUgc} over a list, dropping what does not convert. */
    public static List<String> toUgc(List<String> same) {
        if (same == null || same.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(same.size());
        for (String code : same) {
            String ugc = toUgc(code);
            if (ugc != null && !out.contains(ugc)) out.add(ugc);
        }
        return out;
    }
}
