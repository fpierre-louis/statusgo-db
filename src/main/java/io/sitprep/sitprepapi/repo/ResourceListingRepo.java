package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.ResourceListing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResourceListingRepo extends JpaRepository<ResourceListing, Long> {

    /**
     * Every listing in a given moderation state, newest first. The
     * board fetch pulls APPROVED rows and refines the geo-pinned ones
     * with a Haversine pass in the service layer — the same coarse-JPQL
     * + Java-distance split {@code PostRepo} uses for the post feed.
     */
    List<ResourceListing> findByStatusOrderByCreatedAtDesc(ResourceListing.Status status);

    /** Lookup by the stable natural key — drives idempotent seeding. */
    Optional<ResourceListing> findBySourceKey(String sourceKey);
}
