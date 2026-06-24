package io.sitprep.sitprepapi.dto;

public record AuthorizeAgencyRequestRequest(
        Boolean confirm,
        Double lat,
        Double lng,
        Double radiusMiles
) {}
