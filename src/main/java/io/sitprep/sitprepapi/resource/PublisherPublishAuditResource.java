package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.PublisherPublishAuditDto;
import io.sitprep.sitprepapi.dto.ReviewPublisherPublishAuditRequest;
import io.sitprep.sitprepapi.service.PublisherPublishAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
public class PublisherPublishAuditResource {

    private final PublisherPublishAuditService service;
    private final String adminToken;

    public PublisherPublishAuditResource(PublisherPublishAuditService service,
                                         @Value("${app.admin.token:}") String adminToken) {
        this.service = service;
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    @GetMapping("/api/admin/publisher-publish-reviews")
    public ResponseEntity<List<PublisherPublishAuditDto>> list(
            @RequestParam(value = "status", required = false, defaultValue = "PENDING") String status,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        requireAdmin(token);
        return ResponseEntity.ok(service.listReviews(status));
    }

    @PatchMapping("/api/admin/publisher-publish-reviews/{id}")
    public ResponseEntity<PublisherPublishAuditDto> review(
            @PathVariable Long id,
            @RequestBody ReviewPublisherPublishAuditRequest req,
            @RequestHeader(value = "X-Sitprep-Admin-Token", required = false) String token
    ) {
        requireAdmin(token);
        return ResponseEntity.ok(service.review(id, req, "admin-token"));
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
