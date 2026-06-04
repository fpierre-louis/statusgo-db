package io.sitprep.sitprepapi.dto;

public record ReviewVerificationApplicationRequest(
        String status,
        String reviewerNotes,
        String verifiedKind,
        String approvedPublisherEmail,
        String publisherServiceArea,
        String publisherPermanentAddress,
        String publisherTemporaryEventAddress,
        Boolean emergencyPostingEnabled
) {}
