package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.ResourceCategory;
import io.sitprep.sitprepapi.domain.ResourceListing;
import io.sitprep.sitprepapi.repo.ResourceListingRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the national OFFICIAL resource listings — the always-available
 * hotlines + locators (211, 988, FEMA, Red Cross) — on startup. Keyed
 * by {@code sourceKey} so it is idempotent: an existing row is left
 * alone, a missing one is inserted. Delete a row in the DB and it
 * reappears on the next boot — intentional for these anchors.
 */
@Component
public class ResourceSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ResourceSeeder.class);

    private final ResourceListingRepo repo;

    public ResourceSeeder(ResourceListingRepo repo) {
        this.repo = repo;
    }

    @Override
    public void run(String... args) {
        seed("official:211", "Call 211 for local help", "hotline",
                "Food, housing, utility bills, and disaster services — free, "
                        + "confidential, and available 24/7. 211 knows what's open "
                        + "in your area.",
                "tel:211");
        seed("official:988", "988 Suicide & Crisis Lifeline", "hotline",
                "If you or someone with you is struggling, trained counselors "
                        + "are a call or text away, any hour of the day.",
                "tel:988");
        seed("official:fema-assistance", "FEMA disaster assistance", "recovery",
                "After a federally declared disaster, apply for help with "
                        + "housing, repairs, and essential needs.",
                "https://www.disasterassistance.gov");
        // NOT "medical": someone filtering Medical during an emergency is
        // asking where to get care, and a class signup is not care. "other" is
        // an interim placement — this is preparedness education and probably
        // belongs with the guides rather than on a board whose unit is an
        // actionable endpoint (a number to call, an address to go to). Logged
        // as an open question rather than given a category of its own.
        seed("official:redcross-classes", "CPR & first-aid classes", ResourceCategory.OTHER,
                "The Red Cross runs low-cost and often free training. A few "
                        + "hours now is worth a lot in an emergency.",
                "https://www.redcross.org/take-a-class");
    }

    private void seed(String sourceKey, String title, String category,
                      String description, String contact) {
        // The seeder writes through repo.save() rather than
        // ResourceListingService, so service-side validation cannot reach it.
        // That is precisely how the seeded vocabulary (hotline / recovery)
        // drifted from the submit sheet's nine chips in the first place.
        // Asserting here keeps both writers honest against one definition:
        // a typo or an un-chipped category fails the boot that introduced it,
        // not silently months later on a filter row that has no chip for it.
        if (!ResourceCategory.isValid(category)) {
            throw new IllegalStateException(
                    "ResourceSeeder category '" + category + "' for " + sourceKey
                            + " is not in ResourceCategory.values(). Add the chip in "
                            + "ResourceSubmitSheet.jsx and the icon in "
                            + "ResourceBoardPage.jsx, or use an existing value.");
        }
        if (repo.findBySourceKey(sourceKey).isPresent()) return;
        ResourceListing r = new ResourceListing();
        r.setSourceKey(sourceKey);
        r.setTitle(title);
        r.setCategory(category);
        r.setDescription(description);
        r.setContact(contact);
        r.setSource(ResourceListing.Source.OFFICIAL);
        r.setStatus(ResourceListing.Status.APPROVED);
        repo.save(r);
        log.info("Seeded national resource listing: {}", sourceKey);
    }
}
