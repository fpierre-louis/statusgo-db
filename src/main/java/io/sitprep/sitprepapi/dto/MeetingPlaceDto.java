package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.util.GeoUtil;

/**
 * Read shape for {@link MeetingPlace} (Thin-Client Refactor Phase 3 — DTO
 * hardening). Strips {@code ownerEmail} + {@code householdId}.
 *
 * <p><b>tierKey is intent, not distance.</b> The 4-range {@code tierKey}
 * (near_home / neighborhood / in_town / out_of_area) records which rendezvous
 * slot the user assigned a place to, and several FE surfaces look places up by
 * it ({@code meetingPlaces.find(p =&gt; p.tierKey === key)} in PrintableEvacPage,
 * ActivatePlanDesign, the wizard). It is therefore <b>preserved as stored</b>.</p>
 *
 * <p>This DTO adds the server-computed haversine fields the roadmap asked for,
 * additively: {@code distanceMiles} from the caller's home saved-location (null
 * when either endpoint lacks coordinates) and {@code derivedTierKey} (the
 * distance-bucketed range). {@code tierKey} falls back to {@code derivedTierKey}
 * only when the stored intent is absent (legacy rows) — never overriding an
 * explicit user choice.</p>
 */
public record MeetingPlaceDto(
        Long id,
        String name,
        String location,
        String address,
	        String phoneNumber,
	        String tierKey,
	        String meetingTier,
	        String derivedTierKey,
	        Double distanceMiles,
	        String additionalInfo,
        Double lat,
        Double lng,
        boolean deploy
) {
    public static MeetingPlaceDto from(MeetingPlace p, Double homeLat, Double homeLng) {
        Double distanceMiles = null;
        String derivedTierKey = null;
        if (GeoUtil.validLatLng(p.getLat(), p.getLng())
                && GeoUtil.validLatLng(homeLat, homeLng)) {
            double km = GeoUtil.haversineKm(homeLat, homeLng, p.getLat(), p.getLng());
            double miles = km / GeoUtil.MI_TO_KM;
            distanceMiles = Math.round(miles * 100.0) / 100.0;
            derivedTierKey = tierForMiles(miles);
        }
        String tierKey = (p.getTierKey() != null && !p.getTierKey().isBlank())
                ? p.getTierKey()
                : derivedTierKey;
        return new MeetingPlaceDto(
                p.getId(),
                p.getName(),
                p.getLocation(),
                p.getAddress(),
	                p.getPhoneNumber(),
	                tierKey,
	                p.getMeetingTier() == null ? "OTHER" : p.getMeetingTier().name(),
	                derivedTierKey,
	                distanceMiles,
	                p.getAdditionalInfo(),
                p.getLat(),
                p.getLng(),
                p.isDeploy());
    }

    /**
     * Distance→range buckets (statute miles) mirroring the 4-range meeting
     * model. Kept deliberately coarse: this only fills tierKey for legacy rows
     * with no stored intent, and powers the advisory {@code derivedTierKey}.
     */
    static String tierForMiles(double miles) {
        if (miles < 1.0) return "near_home";
        if (miles < 5.0) return "neighborhood";
        if (miles < 30.0) return "in_town";
        return "out_of_area";
    }
}
