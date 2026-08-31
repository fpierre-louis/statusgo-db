package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write DTO for saved locations.
 *
 * <p>The bug these pin is not a logic error — it is a NAMING asymmetry across
 * the read and write paths of one field, which produced a flag that could never
 * be set and an error nobody ever saw. So the tests are about the wire keys and
 * about absence, not about behaviour.</p>
 */
class UserSavedLocationWriteDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("`isHome` on the wire actually binds — it did not, on the entity")
    void isHomeBinds() throws Exception {
        UserSavedLocationWriteDto in = mapper.readValue(
                "{\"name\":\"Home\",\"latitude\":40.34,\"longitude\":-111.79,\"isHome\":true}",
                UserSavedLocationWriteDto.class);
        assertThat(in.isHome()).isTrue();
        assertThat(in.toNewEntity("a@b.c").isHome()).isTrue();
    }

    @Test
    @DisplayName("the READ dto's key and the WRITE dto's key are the same string")
    void readAndWriteAgree() throws Exception {
        // The whole defect in one assertion. UserSavedLocationDto serialises
        // `isHome`; the entity the write path used to bind knows that key only
        // as `home`, so a client that echoed back what it read was ignored —
        // and silently, because FAIL_ON_UNKNOWN_PROPERTIES is off by default.
        String read = mapper.writeValueAsString(
                new UserSavedLocationDto(1L, "Home", null, 40.34, -111.79, true, null, null, null, null));
        assertThat(read).contains("\"isHome\":true");

        // Lenient, because that is what Spring Boot configures and therefore what
        // a real client hits: FAIL_ON_UNKNOWN_PROPERTIES is off by default, and
        // this app does not override it. A client echoing the whole read payload
        // back sends `id`, `city`, `state` and the rest, which the write DTO
        // deliberately does not accept and must simply ignore. Asserting with a
        // strict mapper here would test Jackson's defaults, not our contract.
        UserSavedLocationWriteDto back = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature
                        .FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(read, UserSavedLocationWriteDto.class);
        assertThat(back.isHome()).isTrue();
        assertThat(back.name()).isEqualTo("Home");
    }

    @Test
    @DisplayName("the entity still does NOT know `isHome` — which is why the DTO exists")
    void entityStillUsesHome() throws Exception {
        // Not a wish for the entity to change: `home` is what Lombok's
        // isHome() getter yields, and renaming the field would ripple through
        // JPA. The point is that the write path must never bind the entity
        // again, and this fails loudly if someone re-points it.
        ObjectMapper lenient = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature
                        .FAIL_ON_UNKNOWN_PROPERTIES, false);
        UserSavedLocation e = lenient.readValue("{\"isHome\":true}", UserSavedLocation.class);
        assertThat(e.isHome())
                .as("entity binding drops isHome — the original defect")
                .isFalse();
        assertThat(lenient.readValue("{\"home\":true}", UserSavedLocation.class).isHome()).isTrue();
    }

    @Test
    @DisplayName("an omitted flag is UNCHANGED, not false")
    void omittedFlagIsNull() throws Exception {
        // The hazard the fix itself introduces. Update is partial — every other
        // field applies only when non-null — so a body that omits the flag must
        // leave it alone. A primitive would arrive as `false` and demote the
        // user's home on any partial edit, e.g. a rename.
        UserSavedLocationWriteDto in = mapper.readValue(
                "{\"name\":\"Renamed\"}", UserSavedLocationWriteDto.class);
        assertThat(in.isHome()).isNull();
        assertThat(in.touchesHome()).isFalse();
    }

    @Test
    @DisplayName("an explicit false IS a demotion, and is distinguishable from absence")
    void explicitFalseIsAnInstruction() throws Exception {
        UserSavedLocationWriteDto in = mapper.readValue(
                "{\"name\":\"X\",\"isHome\":false}", UserSavedLocationWriteDto.class);
        assertThat(in.isHome()).isFalse();
        assertThat(in.touchesHome()).isTrue();
    }

    @Test
    @DisplayName("create treats a missing flag as not-home")
    void createDefaultsToNotHome() {
        assertThat(new UserSavedLocationWriteDto("X", null, 1.0, 2.0, null)
                .toNewEntity("a@b.c").isHome()).isFalse();
    }

    @Test
    @DisplayName("the DTO carries no owner, id or server-derived field")
    void noMassAssignment() {
        // Binding the entity exposed id / ownerEmail / createdAt / updatedAt and
        // the reverse-geocoded columns to the request body; only ownerEmail was
        // defended, by hand. Structure beats a guard: absent fields cannot be set.
        var names = java.util.Arrays.stream(UserSavedLocationWriteDto.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(names).containsExactlyInAnyOrder(
                "name", "address", "latitude", "longitude", "isHome");
    }
}
