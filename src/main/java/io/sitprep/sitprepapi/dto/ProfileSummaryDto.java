package io.sitprep.sitprepapi.dto;

import java.time.Instant;

public record ProfileSummaryDto(
        /**
         * Stable account id. Added 2026-08-22 for @-mentions: a mention stores
         * an id, so the batch identity endpoint has to be able to hand one out.
         *
         * <p>Worth noting what its absence meant -- this is the endpoint every
         * roster and every comment thread resolves identity through, and until
         * now the only identifier it returned was an EMAIL. Anything needing a
         * stable reference had to either use the address as a key (which breaks
         * on change and puts a private value in a public position) or make a
         * second round trip to the full profile.</p>
         */
        String userId,
        String email,
        String firstName,
        String lastName,
        String profileImageUrl,
        String userStatus,
        String statusColor,
        Instant userStatusLastUpdated,
        /** Last time this user hit any authenticated endpoint. Null if never. */
        Instant lastActiveAt,
        /**
         * Verified publisher tier — true means the FE should render a
         * blue checkmark badge next to this user's name everywhere
         * (post header, mention chip, member roster). See
         * docs/SPONSORED_AND_ALERT_MODE.md "The verified-publisher tier".
         */
        boolean verifiedPublisher,
        /**
         * Tier string ({@code city | county | state | newsroom | utility |
         * red-cross | other}) when {@code verifiedPublisher == true},
         * otherwise null. Drives the badge tooltip on hover.
         */
        String verifiedPublisherKind
) {}
