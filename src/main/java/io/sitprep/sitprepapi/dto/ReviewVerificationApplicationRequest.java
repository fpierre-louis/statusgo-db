package io.sitprep.sitprepapi.dto;

public record ReviewVerificationApplicationRequest(
        String status,
        String reviewerNotes,
        String verifiedKind,
        String approvedPublisherEmail,
        String publisherServiceArea,
        String publisherPermanentAddress,
        String publisherTemporaryEventAddress,
        String logoImageUrl,
        Boolean emergencyPostingEnabled,
        // Phase 5 Slice D — stamped on the group at approval (super-admin only).
        // The agency's claimed zips authorize geo-targeted alerts to them.
        java.util.List<String> jurisdictionZips,
        String jurisdictionType,
        Double lat,
        Double lng,
        Double radiusMiles
) {}
