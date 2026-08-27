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
- `MapPoiDto.distanceKm` is documented as viewport-center distance, which is not suitable for “from you” claims.
- Resource listings have source and status, but no stock/freshness/quantity/provenance fields for distribution planning.

## Gaps

- No single active-situation DTO currently coordinates hazard policy, plan CTA priority, meeting-place CTA suppression, and resource CTA priority.
- FE surfaces independently compose active hazards, active plan state, group alerts, and resources.
- A shelter-in-place or evacuation order does not yet centrally govern whether “Navigate to meeting place” should be dominant, secondary, or hidden.
- Resource distribution needs provenance/freshness fields before the map can claim availability.

## Proposed Contract

Introduce a shared active-situation contract that carries:

- active hazard policy decision,
- dominant protective action,
- allowed/suppressed CTAs,
- meeting-place role (`planned`, `recommended`, `unsafe`, `unavailable`, `unknown`),
- check-in freshness and coordinate confidence,
- resource listing freshness/provenance/quantity when known,
- source attribution and degraded-state reasons.
