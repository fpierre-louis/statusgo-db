package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.HazardCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The report sheet renders this list and keeps no copy of its own, so the
 * list IS the app's category set: every category, in enum order, with the
 * values the map draws from (radius) and the detail sheet states (blocks vs
 * warns, lifetime).
 */
class HazardCategoriesResourceTest {

    // categories() reads only the enum; the service is never touched.
    private final HazardResource resource = new HazardResource(null);

    @Test
    @DisplayName("lists every category in enum order with its wire key and label")
    void listsEveryCategory() {
        List<HazardResource.CategoryDto> body = resource.categories().getBody();
        assertThat(body).extracting(HazardResource.CategoryDto::key)
                .containsExactly("fire", "flood", "road_closed", "road_damage",
                        "power_lines", "gas_hazmat", "crash", "debris");
        assertThat(body.get(0).label()).isEqualTo(HazardCategory.FIRE.label());
    }

    @Test
    @DisplayName("carries blocks-vs-warns, radius and lifetime from the enum")
    void carriesTheRuleValues() {
        HazardResource.CategoryDto fire = resource.categories().getBody().get(0);
        assertThat(fire.blocksRoutes()).isTrue();
        assertThat(fire.radiusM()).isEqualTo(800);
        assertThat(fire.lifetimeHours()).isEqualTo(12);

        HazardResource.CategoryDto crash = resource.categories().getBody().stream()
                .filter(c -> c.key().equals("crash")).findFirst().orElseThrow();
        assertThat(crash.blocksRoutes()).isFalse();
        assertThat(crash.lifetimeHours()).isEqualTo(2);
    }
}
