# Active Situation Ecosystem

Date: 2026-08-27

## Current Sources

- External hazards: `AlertIngestService` -> `AlertFeedService` -> `/api/alerts/feed`.
- Group/household active alerts: group alert fields and `useGroupAlertStreams`, composed by `CrisisBand`.
- Active plan: `PlanActivation` plus `PlanActivationService` read projections and STOMP ack stream.
- Check-ins: `PlanActivationAck`, one row per `(activationId, recipientEmail)`, status-first with optional coordinates.
- Meeting places: `MeetingPlace` references on activations and map POIs.
- Evacuation destinations: `EvacuationPlan` references on activations and map POIs.
- Resource listings: `ResourceListingService`, national rows plus geo-pinned local rows.
- Public/community map: `MapDiscoveryService`/`MapPoiDto`, Overpass/resource/group/aid surfaces, and frontend `src/map/*`.

## Confirmed Semantics

- Activation map has separate authenticated owner/household and public recipient projections.
- Public recipient map intentionally returns only meeting place and shelter/destination.
- Owner/household ack rollup is private; recipient link holders do not see other people’s live locations.
- Ack status is primary; bad coordinates degrade to no-location instead of rejecting the status.
- `PlanActivationService` now returns `activeSituation` on owner and recipient
  activation detail DTOs. It carries the requested operational mode, resolved
  operational mode, official movement directive, governing alert summary, active
  meeting/evac destinations, check-in summary when visible to that caller, the
  primary action, and any suppressed plan-navigation action.
- `movementDirective` only affects the primary action when a non-terminal
  governing alert is attached. Missing or expired/superseded/cancelled alert
  context fails closed to `none`.
- `evacuate` and `shelter_in_place` override the requested operational mode to
  `EVACUATING` and `SHELTERING`. `avoid_area` and
  `follow_official_instruction` do not invent a destination mode; they make
  official guidance the primary action and mark saved plan navigation secondary.
- `MapPoiDto.distanceKm` is documented as viewport-center distance, which is not suitable for “from you” claims.
- Resource listings have source and status, but no stock/freshness/quantity/provenance fields for distribution planning.

## Gaps

- Resource distribution needs provenance/freshness/quantity fields before the
  map can claim availability or stock.
- Group-level active alerts still need to feed the same `activeSituation`
  contract when a group activation is driven directly from a live alert.
- Durable alert-post safety snapshots remain separate from the active-situation
  DTO; historical community posts can still be reinterpreted by later policy
  changes unless that snapshot lands.

## Runtime Contract

`activeSituation` is the shared FE/BE contract that carries:

- active hazard policy decision,
- dominant protective action,
- allowed/suppressed CTAs through `primaryAction*` and `suppressed*`,
- meeting-place role (`planned`, `recommended`, `unsafe`, `unavailable`, `unknown`),
- check-in freshness and coordinate confidence,
- resource listing freshness/provenance/quantity when known,
- source attribution and degraded-state reasons.

The first backend slice covers plan CTA priority, governing-alert lifecycle
suppression, and owner-vs-recipient check-in privacy. Meeting-place role and
resource provenance remain explicit follow-ups rather than inferred fields.
