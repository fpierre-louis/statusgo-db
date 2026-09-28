package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.ApiMeta;
import io.sitprep.sitprepapi.dto.ApiResponse;
import io.sitprep.sitprepapi.dto.ThreadContextDto;
import io.sitprep.sitprepapi.service.ThreadContextService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The thread page's viewer context — follow a thread, and who saw it too
 * (Community Thread C, B2 / V86).
 *
 * <pre>
 *   GET    /api/posts/{id}/thread-context  → { viewerFollowing, recentConfirmers[] }
 *   POST   /api/posts/{id}/follow          → { following: true }   (idempotent)
 *   DELETE /api/posts/{id}/follow          → { following: false }  (idempotent)
 * </pre>
 *
 * <p>Its own controller rather than more mappings on {@link PostResource}:
 * these are the thread's reads, not the post's lifecycle. Auth is required
 * (everything under /api/** is), and a post the viewer cannot read answers
 * 404 — the same answer an unknown id gets.</p>
 */
@RestController
public class PostThreadResource {

    private final ThreadContextService threads;

    public PostThreadResource(ThreadContextService threads) {
        this.threads = threads;
    }

    @GetMapping("/api/posts/{id}/thread-context")
    public ResponseEntity<ApiResponse<ThreadContextDto>> context(@PathVariable Long id) {
        String me = AuthUtils.requireAuthenticatedEmail();
        return threads.context(id, me)
                .map(dto -> ResponseEntity.ok(ApiResponse.ok(dto, ApiMeta.now())))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/api/posts/{id}/follow")
    public ResponseEntity<ApiResponse<ThreadContextDto.FollowResult>> follow(@PathVariable Long id) {
        String me = AuthUtils.requireAuthenticatedEmail();
        return threads.follow(id, me)
                .map(r -> ResponseEntity.ok(ApiResponse.ok(r, ApiMeta.now())))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/posts/{id}/follow")
    public ResponseEntity<ApiResponse<ThreadContextDto.FollowResult>> unfollow(@PathVariable Long id) {
        String me = AuthUtils.requireAuthenticatedEmail();
        return threads.unfollow(id, me)
                .map(r -> ResponseEntity.ok(ApiResponse.ok(r, ApiMeta.now())))
                .orElse(ResponseEntity.notFound().build());
    }
}
