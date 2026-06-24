package io.sitprep.sitprepapi.dto;

public record SaveAgencyRequestDraftRequest(
        String verifiedKind,
        String publisherEmail,
        Boolean emergencyPosting,
        String publisherServiceArea,
        String publisherPermanentAddress,
        String publisherTemporaryEventAddress,
        String logoImageUrl,
        Double lat,
        Double lng,
        Double radiusMiles
) {}
