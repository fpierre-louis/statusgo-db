package io.sitprep.sitprepapi.dto;

public record GroupTypingRequest(
        String groupId,
        boolean typing
) {}
