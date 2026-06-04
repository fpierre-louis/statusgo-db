package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.ReviewVerificationApplicationRequest;
import io.sitprep.sitprepapi.dto.SubmitVerificationApplicationRequest;
import io.sitprep.sitprepapi.dto.VerificationApplicationDto;
import io.sitprep.sitprepapi.service.VerificationApplicationService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
public class VerificationApplicationResource {

    private final VerificationApplicationService service;
    private final String adminToken;

    public VerificationApplicationResource(VerificationApplicationService service,
                                           @Value("${app.admin.token:}") String adminToken) {
        this.service = service;
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    @GetMapping("/api/groups/{groupId}/verification-application")
    public ResponseEntity<VerificationApplicationDto> current(@PathVariable String groupId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        VerificationApplicationDto dto = service.currentForGroup(groupId, caller);
        return dto == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(dto);
    }

    @PostMapping("/api/groups/{groupId}/verification-application")
    public ResponseEntity<VerificationApplicationDto> submit(
            @PathVariable String groupId,
            @RequestBody SubmitVerificationApplicationRequest req
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.submit(groupId, caller, req));
    }

    @GetMapping("/api/admin/verification-applications")
    public ResponseEntity<List<VerificationApplicationDto>> adminList(
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        requireAdmin(token);
        return ResponseEntity.ok(service.adminList(status));
    }

    @PatchMapping("/api/admin/verification-applications/{id}")
    public ResponseEntity<VerificationApplicationDto> adminReview(
            @PathVariable Long id,
            @RequestBody ReviewVerificationApplicationRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        requireAdmin(token);
        return ResponseEntity.ok(service.adminReview(id, req, "admin-token"));
    }

    private void requireAdmin(String token) {
        if (adminToken.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Admin endpoints disabled (APP_ADMIN_TOKEN not set)");
        }
        if (token == null || !constantTimeEquals(token, adminToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Admin token required");
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
