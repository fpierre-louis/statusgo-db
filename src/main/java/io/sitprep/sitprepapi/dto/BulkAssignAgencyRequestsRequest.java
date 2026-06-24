package io.sitprep.sitprepapi.dto;

import java.util.List;

public record BulkAssignAgencyRequestsRequest(List<Long> ids, String consultantEmail) {}
