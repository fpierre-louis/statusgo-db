package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.EmergencyContact;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.OriginLocation;
import io.sitprep.sitprepapi.domain.PlanDuration;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Response-shape guards for the Phase 3 DTO-hardening pass. Each DTO is
 * serialized with a real Jackson {@link ObjectMapper} and asserted to
 * (a) expose the FE-facing fields under STABLE names and (b) NOT leak internal
 * owner / household / admin / audit / JPA-back-reference fields.
 *
 * <p>Also pins the {@code isHome} wire key (SYSTEM_TRAPS T-9) and the
 * MeetingPlace haversine + intent-preserving {@code tierKey} semantics.</p>
 */
class PlanDataDtoShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(Object dto) {
        return mapper.valueToTree(dto);
    }

    @Test
    void evacuationPlanDto_exposesPlanFields_hidesOwnerAndHousehold() {
        EvacuationPlan e = new EvacuationPlan();
        e.setId(7L);
        e.setOwnerEmail("owner@x.com");
        e.setHouseholdId("hh-1");
        e.setName("Aunt May's");
        e.setOrigin("Home");
        e.setDestination("Queens");
        e.setDeploy(true);
        e.setShelterName("Red Cross");
        e.setShelterAddress("1 Main St");
        e.setShelterPhoneNumber("555-1212");
        e.setLat(40.7);
        e.setLng(-73.9);
        e.setTravelMode("car");
        e.setShelterInfo("cots available");

        GoBagDtos.GoBagSummaryDto bagSummary = new GoBagDtos.GoBagSummaryDto(
                "bag-1", "Home go bag", "home", "Hallway closet", 26, 53, 49, 2);
        JsonNode n = json(EvacuationPlanDto.from(e, List.of(bagSummary)));
        assertThat(n.has("id")).isTrue();
        assertThat(n.get("name").asText()).isEqualTo("Aunt May's");
        assertThat(n.get("deploy").asBoolean()).isTrue();
        assertThat(n.has("travelMode")).isTrue();
        assertThat(n.has("shelterInfo")).isTrue();
        // internal fields stripped
        assertThat(n.has("ownerEmail")).isFalse();
        assertThat(n.has("householdId")).isFalse();
        // go-bag summaries ride on every plan row (2026-07-09 integration)
        JsonNode bag = n.get("goBags").get(0);
        assertThat(bag.get("name").asText()).isEqualTo("Home go bag");
        assertThat(bag.get("completionPct").asInt()).isEqualTo(49);
        assertThat(bag.get("storageLabel").asText()).isEqualTo("Hallway closet");
    }

    @Test
    void evacuationPlanDto_nullGoBags_serializesEmptyArray() {
        EvacuationPlan e = new EvacuationPlan();
        e.setId(8L);
        JsonNode n = json(EvacuationPlanDto.from(e, null));
        assertThat(n.get("goBags").isArray()).isTrue();
        assertThat(n.get("goBags")).isEmpty();
    }

    @Test
    void demographicDto_exposesCounts_hidesOwnerAdminHousehold() {
        Demographic d = new Demographic();
        d.setId(3L);
        d.setOwnerEmail("owner@x.com");
        d.setHouseholdId("hh-1");
        d.setAdminEmails(List.of("admin@x.com"));
        d.setAdults(2);
        d.setTeens(1);
        d.setKids(2);
        d.setInfants(1);
        d.setDogs(1);

        JsonNode n = json(DemographicDto.from(d));
        assertThat(n.get("adults").asInt()).isEqualTo(2);
        assertThat(n.get("teens").asInt()).isEqualTo(1);
        assertThat(n.get("infants").asInt()).isEqualTo(1);
        // internal fields stripped
        assertThat(n.has("ownerEmail")).isFalse();
        assertThat(n.has("householdId")).isFalse();
        assertThat(n.has("adminEmails")).isFalse();
    }

    @Test
    void originLocationDto_exposesPlace_hidesOwnerHousehold() {
        OriginLocation o = new OriginLocation("Home", "1 Main St", 40.0, -74.0, "owner@x.com");
        o.setId(5L);
        o.setHouseholdId("hh-1");

        JsonNode n = json(OriginLocationDto.from(o));
        assertThat(n.get("name").asText()).isEqualTo("Home");
        assertThat(n.get("lat").asDouble()).isEqualTo(40.0);
        assertThat(n.has("ownerEmail")).isFalse();
        assertThat(n.has("householdId")).isFalse();
    }

    @Test
    void userSavedLocationDto_pinsIsHome_hidesOwnerAuditZip() {
        UserSavedLocation l = new UserSavedLocation();
        l.setId(9L);
        l.setOwnerEmail("owner@x.com");
        l.setName("Home");
        l.setAddress("1 Main St");
        l.setLatitude(40.0);
        l.setLongitude(-74.0);
        l.setHome(true);
        l.setCity("New York");
        l.setRegion("NY");
        l.setState("New York");
        l.setCountry("US");
        l.setZipBucket("100");

        JsonNode n = json(UserSavedLocationDto.from(l));
        // Wire-key quirk (T-9): MUST be "isHome", never Jackson's is-stripped "home".
        assertThat(n.has("isHome")).isTrue();
        assertThat(n.get("isHome").asBoolean()).isTrue();
        assertThat(n.has("home")).isFalse();
        assertThat(n.get("city").asText()).isEqualTo("New York");
        // internal fields stripped
        assertThat(n.has("ownerEmail")).isFalse();
        assertThat(n.has("zipBucket")).isFalse();
        assertThat(n.has("createdAt")).isFalse();
        assertThat(n.has("updatedAt")).isFalse();
    }

    @Test
    void emergencyContactGroupDto_exposesContacts_hidesBackrefAndOwner() {
        EmergencyContactGroup g = new EmergencyContactGroup();
        g.setId(2L);
        g.setOwnerEmail("owner@x.com");
        g.setHouseholdId("hh-1");
        g.setName("Family");
        EmergencyContact c = new EmergencyContact();
        c.setId(11L);
        c.setName("Mom");
        c.setPhone("555-1212");
        c.setMedicalInfo("no allergies");
        g.addContact(c);

        JsonNode n = json(EmergencyContactGroupDto.from(g));
        assertThat(n.get("name").asText()).isEqualTo("Family");
        assertThat(n.has("ownerEmail")).isFalse();
        assertThat(n.has("householdId")).isFalse();
        JsonNode contact = n.get("contacts").get(0);
        assertThat(contact.get("name").asText()).isEqualTo("Mom");
        assertThat(contact.has("medicalInfo")).isTrue();
        // JPA back-reference must not leak (recursion + parent exposure)
        assertThat(contact.has("group")).isFalse();
    }

    @Test
    void mealPlanDto_keepsSelectedItems_hidesOwnerAndBackref() {
        MealPlanData m = new MealPlanData();
        m.setId(4L);
        m.setOwnerEmail("owner@x.com");
        m.setHouseholdId("hh-1");
        m.setNumberOfMenuOptions(1);
        m.setSelectedItemsJson("{\"Water\":true}");
        PlanDuration pd = new PlanDuration();
        pd.setQuantity(3);
        pd.setUnit("Days");
        m.setPlanDuration(pd);
        MealPlan menu = new MealPlan();
        menu.setId(21L);
        menu.setMeals(Map.of("breakfast", "Cereal"));
        menu.setIngredients(Map.of("breakfast", List.of("Cereal", "Milk")));
        m.setMealPlan(List.of(menu));

        JsonNode n = json(MealPlanDto.from(m));
        // selectedItemsJson retained (FE parses it) but now inside a DTO
        assertThat(n.get("selectedItemsJson").asText()).contains("Water");
        assertThat(n.get("planDuration").get("quantity").asInt()).isEqualTo(3);
        assertThat(n.has("ownerEmail")).isFalse();
        assertThat(n.has("householdId")).isFalse();
        JsonNode menuNode = n.get("mealPlan").get(0);
        assertThat(menuNode.has("meals")).isTrue();
        // parent back-reference stripped
        assertThat(menuNode.has("mealPlanData")).isFalse();
    }

    @Test
    void meetingPlaceDto_preservesIntentTierKey_addsHaversine() {
        MeetingPlace p = new MeetingPlace();
        p.setId(1L);
        p.setOwnerEmail("owner@x.com");
        p.setHouseholdId("hh-1");
        p.setName("Friend's house");
        p.setTierKey("near_home"); // explicit user intent
        p.setLat(41.0);
        p.setLng(-74.0);

        // Home ~69 mi south → distance-derived would be out_of_area, but intent wins.
        MeetingPlaceDto dto = MeetingPlaceDto.from(p, 40.0, -74.0);
        assertThat(dto.tierKey()).isEqualTo("near_home");          // intent preserved
        assertThat(dto.derivedTierKey()).isEqualTo("out_of_area"); // haversine advisory
        assertThat(dto.distanceMiles()).isBetween(67.5, 70.5);

        JsonNode n = json(dto);
        assertThat(n.has("ownerEmail")).isFalse();
        assertThat(n.has("householdId")).isFalse();
        assertThat(n.has("distanceMiles")).isTrue();
    }

    @Test
    void meetingPlaceDto_fillsTierKeyFromDistance_whenIntentMissing() {
        MeetingPlace p = new MeetingPlace();
        p.setName("Backyard");
        p.setTierKey(null); // legacy row, no intent
        p.setLat(40.0);
        p.setLng(-74.0);

        MeetingPlaceDto dto = MeetingPlaceDto.from(p, 40.0, -74.0); // same as home → 0 mi
        assertThat(dto.distanceMiles()).isEqualTo(0.0);
        assertThat(dto.derivedTierKey()).isEqualTo("near_home");
        assertThat(dto.tierKey()).isEqualTo("near_home"); // fell back to derived
    }

    @Test
    void meetingPlaceDto_nullDistanceWhenNoHomeCoords() {
        MeetingPlace p = new MeetingPlace();
        p.setTierKey("in_town");
        p.setLat(40.0);
        p.setLng(-74.0);

        MeetingPlaceDto dto = MeetingPlaceDto.from(p, null, null);
        assertThat(dto.distanceMiles()).isNull();
        assertThat(dto.derivedTierKey()).isNull();
        assertThat(dto.tierKey()).isEqualTo("in_town"); // stored intent preserved
    }

    @Test
    void tierForMiles_bucketsMatchFourRangeModel() {
        assertThat(MeetingPlaceDto.tierForMiles(0.5)).isEqualTo("near_home");
        assertThat(MeetingPlaceDto.tierForMiles(3.0)).isEqualTo("neighborhood");
        assertThat(MeetingPlaceDto.tierForMiles(15.0)).isEqualTo("in_town");
        assertThat(MeetingPlaceDto.tierForMiles(50.0)).isEqualTo("out_of_area");
    }
}
