package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.AgencyAlertResultDto;
import io.sitprep.sitprepapi.dto.SendAgencyAlertRequest;
import io.sitprep.sitprepapi.service.AgencyAlertService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Geo-targeted agency alert send (Phase 5 Slice D). Group-scoped so the
 * service can authorize the caller against that group; all authority +
 * zip-clamping + idempotency live in {@link AgencyAlertService}.
 */
@RestController
public class AgencyAlertResource {

    private final AgencyAlertService service;

    public AgencyAlertResource(AgencyAlertService service) {
        this.service = service;
    }

    @PostMapping("/api/groups/{groupId}/agency-alerts")
    public ResponseEntity<AgencyAlertResultDto> send(@PathVariable String groupId,
                                                     @RequestBody SendAgencyAlertRequest req) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        AgencyAlertResultDto result = service.send(groupId, caller, req);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }
}
