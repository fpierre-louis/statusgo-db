# Emergency Live Location Backend Audit

Date: 2026-09-09

## Current Server Capability

- `PATCH /api/userinfo/me/location` stores the verified caller's latest device
  fix on `UserInfo.lastKnownLat`, `lastKnownLng`, and `lastKnownLocationAt`.
- `UserInfo.lastKnownZip` is refreshed from reverse geocoding when the user
  moves meaningfully, which supports jurisdiction and alert routing.
- `UserInfo.groupLocationSharing` stores per-group visibility preferences:
  `always`, `check-in-only`, and `never`.
- `LocationSharing` owns the privacy gate. Unset household rows resolve to
  `check-in-only`, unset non-household rows resolve to `never`, and explicit
  `never` is absolute even during active alerts.
- `GroupViewService` strips member coordinates when the gate says a group
  cannot see that member's location.
- `UserInfoService.updateLastKnownLocationByEmail` broadcasts
  `MemberLocationFrame` to `/topic/group/{groupId}/members/location` only for
  groups currently allowed to see that user's location.

## Implemented Live Session Model

The server now has an emergency live-location session model. Reusing
`UserInfo.lastKnownLat/Lng` remains appropriate for low-frequency presence, but
active emergency sharing is tracked separately so SitPrep can answer which
groups were selected, when sharing expires, whether the user stopped, and
whether a point is stale.

When a client starts a session with an `activationId` and no explicit
`groupIds`, the server resolves the activation owner's base household and uses
that as the session's group scope. The caller still must be a member of that
household, and the user's location-sharing preference still applies.

`live_location_sessions`

- `id`
- `user_email`
- `scope_type`
- `group_ids`
- `activation_id` or `alert_id`
- `started_at`
- `expires_at`
- `stopped_at`
- `upload_token_hash`
- `created_by_user`

`live_location_points`

- `id`
- `session_id`
- `user_email`
- `lat`
- `lng`
- `accuracy_m`
- `speed_mps`
- `heading_deg`
- `captured_at`
- `created_at`

## Implemented API

- `POST /api/live-location/sessions`
  Starts a user-approved emergency sharing session and returns a scoped
  `uploadToken` once.
- `PATCH /api/live-location/sessions/{id}/point`
  Writes the caller's latest point for that session. Regular app calls use
  Firebase auth; native background uploads can use `X-Live-Location-Token`.
- `POST /api/live-location/sessions/{id}/stop`
  Stops sharing immediately through Firebase auth or the scoped upload token.
- `GET /api/groups/{groupId}/live-locations`
  Returns latest allowed points for the group, not full track history.

## Rules To Preserve

- A user must explicitly start live sharing.
- A group admin cannot silently turn on another member's live location.
- `never` remains absolute.
- `check-in-only` reveals location only while the relevant alert/session is
  active.
- Stale or stopped sessions should stop delivering points.
- WebSocket frames must remain group-scoped and privacy-gated.
- Historical tracks should not be exposed unless the product intentionally adds
  a separate history feature with its own privacy review.
