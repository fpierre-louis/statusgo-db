package io.sitprep.sitprepapi.gamification;

import java.time.Instant;
import java.util.List;

/** Wire shapes for {@code /api/me/tokens}. */
public final class TokenDtos {

    private TokenDtos() {}

    /** A catalog entry, as the client renders it. */
    public record CatalogEntry(
            String key,
            String scope,
            String name,
            String description,
            String lockedHint,
            String iconKey,
            String nextStepRoute,
            int order
    ) {
        static CatalogEntry of(TokenDefinition d) {
            return new CatalogEntry(d.key().name(), d.scope().name(), d.name(), d.description(),
                    d.lockedHint(), d.iconKey(), d.nextStepRoute(), d.order());
        }
    }

    /**
     * One earned token. {@code id} is opaque ({@code user:12} / {@code household:7}):
     * the two ledgers number their rows independently, so a bare number would be
     * ambiguous when sent back to {@code /seen}.
     *
     * @param earnedByName first name of the member whose action earned a household
     *                     token; null for personal tokens and unknown members
     * @param earnedByYou  true when the viewer earned it
     */
    public record Award(
            String id,
            String scope,
            String tokenKey,
            Instant earnedAt,
            boolean seen,
            String earnedByName,
            boolean earnedByYou
    ) {}

    public record TokensResponse(
            List<CatalogEntry> catalog,
            List<Award> userTokens,
            List<Award> householdTokens,
            List<Award> recentUnseen,
            String householdId
    ) {}

    public record SeenRequest(List<String> awardIds) {}

    public record SeenResponse(int marked) {}
}
