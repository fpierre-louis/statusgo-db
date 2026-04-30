package io.sitprep.sitprepapi.dto;

import java.time.Instant;

public record ProfileSummaryDto(
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
