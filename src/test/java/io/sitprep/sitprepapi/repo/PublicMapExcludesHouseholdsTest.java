package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.Group;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The community map prints a guarantee to every viewer — "Public data only.
 * Other people's locations are never shown here." Before 2026-08-25 the only
 * thing keeping a family's home coordinate off that map was the group's
 * {@code privacy} string, a mutable free-form column editable from the general
 * group-settings screen. Two households were measured on the live map.
 *
 * <p>{@link GroupRepo#findPublicInBounds} now excludes households structurally.
 * These tests exist because that predicate is the whole control: the flag is no
 * longer load-bearing, so this query is.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class PublicMapExcludesHouseholdsTest {

    @Autowired
    GroupRepo groupRepo;

    /** A generous box around the Utah corridor where the two live rows sit. */
    private static final double MIN_LAT = 40.0, MAX_LAT = 41.0;
    private static final double MIN_LNG = -112.5, MAX_LNG = -111.0;

    private Group save(String type, String privacy) {
        Group g = new Group();
        g.setGroupId(UUID.randomUUID().toString());
        g.setGroupName("t-" + UUID.randomUUID());
        g.setGroupType(type);
        g.setPrivacy(privacy);
        g.setLatitude(40.39);
        g.setLongitude(-111.90);
        return groupRepo.save(g);
    }

    private List<String> idsInBox() {
        return groupRepo.findPublicInBounds(MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG)
                .stream().map(Group::getGroupId).toList();
    }

    @Test
    void aPublicHouseholdIsNotOnTheCommunityMap() {
        Group household = save("Household", "Public");
        assertThat(idsInBox()).doesNotContain(household.getGroupId());
    }

    @Test
    void theExclusionDoesNotDependOnHowTheTypeIsCased() {
        // groupType is a free-form varchar with no enum and no server-side
        // validation, so "household" and "HOUSEHOLD" are both reachable values.
        // A case-sensitive predicate would leak on the ones that are not
        // spelled the way the constant is.
        Group lower = save("household", "Public");
        Group upper = save("HOUSEHOLD", "Public");
        assertThat(idsInBox())
                .doesNotContain(lower.getGroupId())
                .doesNotContain(upper.getGroupId());
    }

    @Test
    void aPublicOrgIsStillOnTheMap() {
        // The fix has to be an exclusion, not a narrowing. If this fails the
        // community map is empty and the "leak" is fixed by deleting the feature.
        Group business = save("Business", "Public");
        assertThat(idsInBox()).contains(business.getGroupId());
    }

    @Test
    void aPublicGroupWithNoTypeAtAllIsStillOnTheMap() {
        // THE ONE THAT JUSTIFIES THE COALESCE. groupType is nullable, and in SQL
        // `NULL <> 'household'` evaluates to NULL rather than TRUE — so a bare
        // inequality silently drops every group whose type was never set. That
        // would remove real organisations from the community map as a side
        // effect of fixing a household leak, and nothing would error.
        Group untyped = save(null, "Public");
        assertThat(idsInBox()).contains(untyped.getGroupId());
    }

    @Test
    void privateGroupsAreStillExcluded() {
        // The household predicate is additional to the privacy gate, not a
        // replacement for it.
        Group privateOrg = save("Business", "Private");
        assertThat(idsInBox()).doesNotContain(privateOrg.getGroupId());
    }
}
