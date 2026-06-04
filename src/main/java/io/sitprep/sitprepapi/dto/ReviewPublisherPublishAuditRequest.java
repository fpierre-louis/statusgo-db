package io.sitprep.sitprepapi.dto;

public record ReviewPublisherPublishAuditRequest(
        String status,
        String reviewerNotes
) {}
