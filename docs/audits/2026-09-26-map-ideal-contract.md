# Map Ideal API contract audit

Date: 2026-09-26

This audit checks the Map Ideal handoff against the current backend before schema work.

## Backed now

| UI claim | Backend source |
| --- | --- |
| Person photo or initials fallback | `GroupMemberViewDto.MemberSummary.profileImageUrl`, first name, last name |
| Status and last response | `MemberSummary.selfStatus.updatedAt` and `lastKnownLocationAt` |
| Check-in requested but unanswered | `checkInRequestedAt` and `checkInDispatch` |
| Household accountability totals | `GroupMemberViewDto.StatusRollup` |
| Group default imagery | group type plus uploaded image fields; FE owns the canonical type-emblem fallback |
| Agency identity | agency flag, verified kind, publisher identity, and resolved profile imagery |
| Mutual face stack | `CommunityDiscoverDto.NearbyGroup.mutualMembers` |
| Alert plain language | `AlertCardDto.headline`, `whatToDo`, and `precautions` |
| Official wording | `AlertCardDto.official` |
| Alert timing | `effectiveAt` and nullable `expiresAt` |
| Alert locality/precision | `AlertCardDto.location.matchType`, `confidence`, and map geometry pipeline |
| Community confirmations | `PostDto.CommunityExtras.confirmsCount` and `viewerConfirmed` |

## Deliberately not inferred

- No center pin is synthesized for a region alert without geometry.
- No `DELIVERED` check-in state is inferred from FCM acceptance.
- No priority reason is generated from post wording. Ranking may use existing priority, proximity, recency, and confirmation fields, but the UI cannot present a reason unless the service supplies one.
- No default photo URL is stored. The frontend renders stable initials or an entity-type emblem so a fallback cannot be mistaken for uploaded identity.

## Decision

No database migration is required for this pass. The current API supports the claims shipped by the redesigned map. A future explicit, server-authored `priorityReason` needs a product definition and separate contract review.
