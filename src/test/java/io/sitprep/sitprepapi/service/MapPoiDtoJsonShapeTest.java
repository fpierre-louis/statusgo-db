package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.constant.AgencyCapability;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MapPoiDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.PublicCdn;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the wire shape of every {@link MapPoiDto} kind (map pins v2, P2b).
 *
 * <p>Each expected string was captured from the code BEFORE the 2026-10-13
 * named-builder refactor, then had exactly one key appended: {@code imageUrl}.
 * So a pass means the refactor moved no value between keys. The mapper is
 * Spring's builder default, the same one the capture ran with (it writes
 * {@code createdAt} as epoch seconds; Boot's runtime mapper writes ISO, which
 * is mapper config, not DTO shape).</p>
 */
class MapPoiDtoJsonShapeTest {

    static final Instant T = Instant.parse("2026-09-26T18:30:00Z");
    static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build();

    static Map<String, MapPoiDto> discovered(String viewer) {
        GroupRepo groupRepo = mock(GroupRepo.class);
        PostRepo postRepo = mock(PostRepo.class);
        UserInfoRepo userRepo = mock(UserInfoRepo.class);
        ExternalPoiCacheService external = mock(ExternalPoiCacheService.class);
        when(external.getPois(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());

        Group city = new Group();
        city.setGroupId("city");
        city.setGroupName("City of Lehi");
        city.setGroupType("Agency");
        city.setAgencyAuthorized(true);
        city.setAgencyCapabilities(EnumSet.of(AgencyCapability.SEND_AREA_ALERTS));
        city.setLatitude(40.39);
        city.setLongitude(-111.85);
        city.setCreatedAt(T);
        city.setMemberEmails(new ArrayList<>(List.of("a@x.com", "b@x.com")));
        city.setLogoImageUrl("https://sitprepimages.com/logo/city.png");

        Group hoa = new Group();
        hoa.setGroupId("hoa");
        hoa.setGroupName("Dry Creek HOA");
        hoa.setGroupType("HOA/Neighborhood");
        hoa.setLatitude(40.40);
        hoa.setLongitude(-111.86);
        hoa.setCreatedAt(T.plusSeconds(5));
        hoa.setMemberEmails(new ArrayList<>(List.of("me@x.com")));
        hoa.setLogoImageUrl("  ");

        when(groupRepo.findPublicInBounds(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(city, hoa));
        when(groupRepo.findAllById(anyList())).thenReturn(List.of());

        Post aid = new Post();
        aid.setId(7L);
        aid.setKind("offer");
        aid.setPriority(Post.PostPriority.URGENT);
        aid.setRequesterEmail("frank@x.com");
        aid.setTitle("Spare generator");
        aid.setDescription("Running, bring a can");
        aid.setPlaceLabel("Dry Creek");
        aid.setLatitude(40.391);
        aid.setLongitude(-111.851);
        aid.setCreatedAt(T.plusSeconds(7));
        aid.setImageKeys(new ArrayList<>(List.of("post/abc.jpg", "post/def.jpg")));
        when(postRepo.findAidInBounds(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(aid));

        UserInfo frank = new UserInfo();
        frank.setUserEmail("frank@x.com");
        frank.setUserFirstName("Frank");
        frank.setUserLastName("Diaz");
        when(userRepo.findByUserEmailIn(anyList())).thenReturn(List.of(frank));

        MapDiscoveryService discovery = new MapDiscoveryService(groupRepo, postRepo, userRepo, external, null);
        Map<String, MapPoiDto> out = new HashMap<>();
        for (MapPoiDto p : discovery.discover(40.3, -111.95, 40.5, -111.75, 14, viewer).pois()) {
            out.put(p.id(), p);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static MapPoiDto overpass() throws Exception {
        OverpassService svc = new OverpassService(new ObjectMapper());
        Method parse = OverpassService.class.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        String body = "{\"elements\":[{\"type\":\"node\",\"id\":42,\"lat\":40.1,\"lon\":-111.9,"
                + "\"tags\":{\"name\":\"Harvest Park\",\"leisure\":\"park\",\"website\":\"https://park.example\"}}]}";
        return ((List<MapPoiDto>) parse.invoke(svc, body)).get(0);
    }

    static MapPoiDto activation() throws Exception {
        Method m = PlanActivationService.class.getDeclaredMethod("planPoi",
                String.class, String.class, String.class, String.class, Double.class, Double.class, String.class);
        m.setAccessible(true);
        return (MapPoiDto) m.invoke(null, "activation:shelter:2", "shelter", "shelter-primary",
                "Red Cross", 40.2, -111.2, "5 Safe Ave");
    }

    private static final String BEFORE_AGENCY = "{\"id\":\"group:city\",\"family\":\"agency\",\"source\":\"proprietary:group\",\"name\":\"City of Lehi\",\"lat\":40.39,\"lng\":-111.85,\"distanceKm\":1.1,\"verified\":false,\"verifiedKind\":null,\"memberCount\":2,\"viewerRole\":\"NONE\",\"ownerUserId\":null,\"groupType\":\"Agency\",\"postId\":null,\"kind\":null,\"description\":null,\"placeLabel\":null,\"category\":null,\"website\":null,\"externalMapUrl\":null,\"attribution\":null,\"logoImageUrl\":\"https://sitprepimages.com/logo/city.png\",\"priorityReason\":null,\"createdAt\":1790447400.000000000,\"authorDisplayName\":null,\"canSendAreaAlerts\":true,\"confirmations\":null";
    private static final String BEFORE_GROUP = "{\"id\":\"group:hoa\",\"family\":\"group\",\"source\":\"proprietary:group\",\"name\":\"Dry Creek HOA\",\"lat\":40.4,\"lng\":-111.86,\"distanceKm\":0.8,\"verified\":false,\"verifiedKind\":null,\"memberCount\":1,\"viewerRole\":\"MEMBER\",\"ownerUserId\":null,\"groupType\":\"HOA/Neighborhood\",\"postId\":null,\"kind\":null,\"description\":null,\"placeLabel\":null,\"category\":null,\"website\":null,\"externalMapUrl\":null,\"attribution\":null,\"logoImageUrl\":null,\"priorityReason\":null,\"createdAt\":1790447405.000000000,\"authorDisplayName\":null,\"canSendAreaAlerts\":null,\"confirmations\":null";
    private static final String BEFORE_AID_SIGNED_IN = "{\"id\":\"post:7\",\"family\":\"aid\",\"source\":\"proprietary:post\",\"name\":\"Spare generator\",\"lat\":40.391,\"lng\":-111.851,\"distanceKm\":1.0,\"verified\":null,\"verifiedKind\":null,\"memberCount\":null,\"viewerRole\":null,\"ownerUserId\":null,\"groupType\":null,\"postId\":7,\"kind\":\"offer\",\"description\":\"Running, bring a can\",\"placeLabel\":\"Dry Creek\",\"category\":null,\"website\":null,\"externalMapUrl\":null,\"attribution\":null,\"logoImageUrl\":null,\"priorityReason\":\"poster-urgent\",\"createdAt\":1790447407.000000000,\"authorDisplayName\":\"Frank Diaz\",\"canSendAreaAlerts\":null,\"confirmations\":null";
    private static final String BEFORE_AID_GUEST = "{\"id\":\"post:7\",\"family\":\"aid\",\"source\":\"proprietary:post\",\"name\":\"Spare generator\",\"lat\":40.391,\"lng\":-111.851,\"distanceKm\":1.0,\"verified\":null,\"verifiedKind\":null,\"memberCount\":null,\"viewerRole\":null,\"ownerUserId\":null,\"groupType\":null,\"postId\":7,\"kind\":\"offer\",\"description\":\"Running, bring a can\",\"placeLabel\":\"Dry Creek\",\"category\":null,\"website\":null,\"externalMapUrl\":null,\"attribution\":null,\"logoImageUrl\":null,\"priorityReason\":\"poster-urgent\",\"createdAt\":1790447407.000000000,\"authorDisplayName\":null,\"canSendAreaAlerts\":null,\"confirmations\":null";
    private static final String BEFORE_OVERPASS = "{\"id\":\"overpass:node/42\",\"family\":\"park\",\"source\":\"overpass\",\"name\":\"Harvest Park\",\"lat\":40.1,\"lng\":-111.9,\"distanceKm\":null,\"verified\":null,\"verifiedKind\":null,\"memberCount\":null,\"viewerRole\":null,\"ownerUserId\":null,\"groupType\":null,\"postId\":null,\"kind\":null,\"description\":null,\"placeLabel\":null,\"category\":\"park\",\"website\":\"https://park.example\",\"externalMapUrl\":\"https://www.google.com/maps/search/?api=1&query=40.1,-111.9\",\"attribution\":\"© OpenStreetMap contributors\",\"logoImageUrl\":null,\"priorityReason\":null,\"createdAt\":null,\"authorDisplayName\":null,\"canSendAreaAlerts\":null,\"confirmations\":null";
    private static final String BEFORE_ACTIVATION = "{\"id\":\"activation:shelter:2\",\"family\":\"shelter\",\"source\":\"proprietary:activation\",\"name\":\"Red Cross\",\"lat\":40.2,\"lng\":-111.2,\"distanceKm\":null,\"verified\":null,\"verifiedKind\":null,\"memberCount\":null,\"viewerRole\":null,\"ownerUserId\":null,\"groupType\":null,\"postId\":null,\"kind\":null,\"description\":\"5 Safe Ave\",\"placeLabel\":\"shelter-primary\",\"category\":null,\"website\":null,\"externalMapUrl\":null,\"attribution\":null,\"logoImageUrl\":null,\"priorityReason\":null,\"createdAt\":null,\"authorDisplayName\":null,\"canSendAreaAlerts\":null,\"confirmations\":null";

    private static String plusImage(String before, String url) {
        return before + ",\"imageUrl\":" + (url == null ? "null" : "\"" + url + "\"") + "}";
    }

    @Test
    void everyKindIsItsPreRefactorShapePlusImageUrlLast() throws Exception {
        Map<String, MapPoiDto> in = discovered("me@x.com");
        Map<String, MapPoiDto> guest = discovered(null);
        String photo = PublicCdn.baseUrl() + "/post/abc.jpg";

        assertThat(JSON.writeValueAsString(in.get("group:city"))).isEqualTo(plusImage(BEFORE_AGENCY, null));
        assertThat(JSON.writeValueAsString(in.get("group:hoa"))).isEqualTo(plusImage(BEFORE_GROUP, null));
        assertThat(JSON.writeValueAsString(in.get("post:7")))
                .as("signed in: the first of two photos")
                .isEqualTo(plusImage(BEFORE_AID_SIGNED_IN, photo));
        assertThat(JSON.writeValueAsString(guest.get("post:7")))
                .as("guest: the feed is signed-in only, so no photo")
                .isEqualTo(plusImage(BEFORE_AID_GUEST, null));
        assertThat(JSON.writeValueAsString(overpass())).isEqualTo(plusImage(BEFORE_OVERPASS, null));
        assertThat(JSON.writeValueAsString(activation())).isEqualTo(plusImage(BEFORE_ACTIVATION, null));
    }

    @Test
    void toBuilderCopiesEveryField() throws Exception {
        MapPoiDto aid = discovered("me@x.com").get("post:7");
        assertThat(aid.toBuilder().build()).isEqualTo(aid);
        assertThat(aid.withConfirmations(null)).isEqualTo(aid);
        var c = new io.sitprep.sitprepapi.dto.MapConfirmationDtos.ConfirmationSummary(3, T);
        MapPoiDto confirmed = aid.withConfirmations(c);
        assertThat(confirmed.confirmations()).isEqualTo(c);
        assertThat(confirmed.toBuilder().confirmations(null).build()).isEqualTo(aid);
    }

    @Test
    void firstImageUrlSkipsBlanksAndSendsOnlyHttp() {
        Post p = new Post();
        assertThat(MapDiscoveryService.firstImageUrl(null)).isNull();
        p.setImageKeys(null);
        assertThat(MapDiscoveryService.firstImageUrl(p)).isNull();
        p.setImageKeys(new ArrayList<>());
        assertThat(MapDiscoveryService.firstImageUrl(p)).as("no photo, no frame").isNull();
        p.setImageKeys(new ArrayList<>(java.util.Arrays.asList(null, " ", "post/x.jpg")));
        assertThat(MapDiscoveryService.firstImageUrl(p)).isEqualTo(PublicCdn.baseUrl() + "/post/x.jpg");
        p.setImageKeys(new ArrayList<>(List.of("data:image/png;base64,AAAA", "post/y.jpg")));
        assertThat(MapDiscoveryService.firstImageUrl(p)).as("the FIRST photo is not a URL: send none").isNull();
    }

    @Test
    void aHiddenAuthorKeepsThePhotoButIsNeverNamed() {
        GroupRepo groupRepo = mock(GroupRepo.class);
        PostRepo postRepo = mock(PostRepo.class);
        UserInfoRepo userRepo = mock(UserInfoRepo.class);
        ExternalPoiCacheService external = mock(ExternalPoiCacheService.class);
        when(external.getPois(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
        when(groupRepo.findPublicInBounds(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
        Post hidden = new Post();
        hidden.setId(9L);
        hidden.setKind("offer");
        hidden.setRequesterEmail("frank@x.com");
        hidden.setAuthorHidden(true);
        hidden.setTitle("Sandbags");
        hidden.setLatitude(40.391);
        hidden.setLongitude(-111.851);
        hidden.setImageKeys(new ArrayList<>(List.of("post/bags.jpg")));
        when(postRepo.findAidInBounds(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(hidden));
        UserInfo frank = new UserInfo();
        frank.setUserEmail("frank@x.com");
        frank.setUserFirstName("Frank");
        when(userRepo.findByUserEmailIn(anyList())).thenReturn(List.of(frank));
        MapDiscoveryService discovery = new MapDiscoveryService(groupRepo, postRepo, userRepo, external, null);

        MapPoiDto other = discovery.discover(40.3, -111.95, 40.5, -111.75, 14, "me@x.com").pois().get(0);
        assertThat(other.authorDisplayName()).isNull();
        assertThat(other.imageUrl()).isEqualTo(PublicCdn.baseUrl() + "/post/bags.jpg");

        MapPoiDto own = discovery.discover(40.3, -111.95, 40.5, -111.75, 14, "frank@x.com").pois().get(0);
        assertThat(own.authorDisplayName()).as("the author sees their own name, as on the feed").isEqualTo("Frank");
    }

    /**
     * No service may build a MapPoiDto positionally. The canonical constructor
     * must stay public (a public record requires it), so this scan is the guard.
     * PlanActivationService is allowlisted until its lane frees (2026-10-13).
     */
    @Test
    void noPositionalConstructionOutsideTheRecord() throws Exception {
        Path main = Path.of("src/main/java");
        Set<String> allowed = Set.of("MapPoiDto.java", "PlanActivationService.java");
        List<String> offenders;
        try (var files = Files.walk(main)) {
            offenders = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !allowed.contains(f.getFileName().toString()))
                    .filter(f -> {
                        try { return Files.readString(f).contains("new MapPoiDto("); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    })
                    .map(Path::toString).toList();
        }
        assertThat(offenders).as("use MapPoiDto.builder()").isEmpty();
    }
}
