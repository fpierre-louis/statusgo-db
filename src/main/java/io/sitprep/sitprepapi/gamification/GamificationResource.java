package io.sitprep.sitprepapi.gamification;

import io.sitprep.sitprepapi.gamification.TokenDtos.SeenRequest;
import io.sitprep.sitprepapi.gamification.TokenDtos.SeenResponse;
import io.sitprep.sitprepapi.gamification.TokenDtos.TokensResponse;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Readiness Tokens — the viewer's own, only (docs/epics/readiness_tokens).
 *
 * <ul>
 *   <li>{@code GET /api/me/tokens} → catalog, earned personal + household tokens,
 *       and the unlocks the viewer has not seen yet.</li>
 *   <li>{@code POST /api/me/tokens/seen {awardIds}} → {@code {marked}}; ids the
 *       viewer does not own are ignored.</li>
 * </ul>
 * 401 without a verified token. Awards are written by the evaluator, never here.
 */
@RestController
@RequestMapping("/api/me/tokens")
public class GamificationResource {

    private final TokenReadService tokens;

    public GamificationResource(TokenReadService tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    public ResponseEntity<TokensResponse> myTokens() {
        return ResponseEntity.ok(tokens.forViewer(AuthUtils.requireAuthenticatedEmail()));
    }

    @PostMapping("/seen")
    public ResponseEntity<SeenResponse> markSeen(@RequestBody(required = false) SeenRequest req) {
        String email = AuthUtils.requireAuthenticatedEmail();
        int marked = tokens.markSeen(email, req == null ? null : req.awardIds());
        return ResponseEntity.ok(new SeenResponse(marked));
    }
}
