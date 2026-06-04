package io.sitprep.sitprepapi.dto;

public record ReviewVerificationApplicationRequest(
        String status,
        String reviewerNotes,
        String verifiedKind,
        String approvedPublisherEmail
) {}
