package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.LiveLocationDtos.LiveLocationMemberDto;
import io.sitprep.sitprepapi.dto.LiveLocationDtos.LiveLocationPointRequest;
import io.sitprep.sitprepapi.dto.LiveLocationDtos.LiveLocationSessionDto;
import io.sitprep.sitprepapi.dto.LiveLocationDtos.StartLiveLocationSessionRequest;
import io.sitprep.sitprepapi.service.LiveLocationService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:3000")
public class LiveLocationResource {

    private final LiveLocationService service;

    public LiveLocationResource(LiveLocationService service) {
        this.service = service;
    }

    @PostMapping("/live-location/sessions")
    public LiveLocationSessionDto start(@RequestBody StartLiveLocationSessionRequest request) {
        return service.start(AuthUtils.requireAuthenticatedEmail(), request);
    }

    @GetMapping("/live-location/sessions/mine")
    public List<LiveLocationSessionDto> mine() {
        return service.listMine(AuthUtils.requireAuthenticatedEmail());
    }

    @PatchMapping("/live-location/sessions/{sessionId}/point")
    public LiveLocationMemberDto updatePoint(@PathVariable String sessionId,
                                             @RequestHeader(value = "X-Live-Location-Token", required = false)
                                             String uploadToken,
                                             @RequestBody LiveLocationPointRequest request) {
        if (uploadToken != null && !uploadToken.isBlank()) {
            return service.updatePointWithUploadToken(sessionId, uploadToken, request);
        }
        return service.updatePoint(AuthUtils.requireAuthenticatedEmail(), sessionId, request);
    }

    @PostMapping("/live-location/sessions/{sessionId}/stop")
    public LiveLocationSessionDto stop(@PathVariable String sessionId,
                                       @RequestHeader(value = "X-Live-Location-Token", required = false)
                                       String uploadToken) {
        if (uploadToken != null && !uploadToken.isBlank()) {
            return service.stopWithUploadToken(sessionId, uploadToken);
        }
        return service.stop(AuthUtils.requireAuthenticatedEmail(), sessionId);
    }

    @GetMapping("/groups/{groupId}/live-locations")
    public List<LiveLocationMemberDto> listForGroup(@PathVariable String groupId) {
        return service.listForGroup(AuthUtils.requireAuthenticatedEmail(), groupId);
    }
}
