package io.sitprep.sitprepapi.service;

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
        seed("official:redcross-classes", "CPR & first-aid classes", "medical",
                "The Red Cross runs low-cost and often free training. A few "
                        + "hours now is worth a lot in an emergency.",
                "https://www.redcross.org/take-a-class");
    }

    private void seed(String sourceKey, String title, String category,
                      String description, String contact) {
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
