package io.sitprep.sitprepapi.dto;

public record ReviewCommunityReportRequest(
        String status,
        String reviewerNotes
) {}
