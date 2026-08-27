# Active Situation UX Contract

Date: 2026-08-27

## Core Rules

- Official instructions outrank SitPrep guidance on every surface.
- A map may show the same emergency state in different formats, but it must not reinterpret it independently.
- A planned meeting place is not automatically a recommended destination during an active alert.
- Check-ins are statuses first and locations second.
- A city centroid or viewport-derived distance is not a person/location claim.
- Resource availability must carry freshness/provenance before the UI implies stock is available now.

## CTA Priority

- `EVACUATE`: evacuation/destination actions outrank meeting-place navigation unless the official instruction names the meeting point.
- `SHELTER`: shelter/stay-put actions outrank route or meeting-place CTAs.
- `AVOID`: avoid-area guidance should keep route CTAs secondary and avoid drawing a path through the hazard when route hazard intersection is unknown.
- `PREPARE`/`MONITOR`: meeting-place and readiness CTAs may remain visible, but must not read as an official instruction.
- `ALL_CLEAR`/cancel/expired/test/non-public: no ordinary public emergency CTA.

## Runtime Wires

`PlanActivation.activeSituation` is the canonical activation state for Home,
group surfaces, deployed-plan pages, and map previews. Frontend surfaces may
format it differently, but must not recompute official movement priority from
`protectiveAction`, tier, severity, or raw alert text.

- `requestedOperationalMode`: the mode the user/plan requested before official
  movement rules are applied.
- `operationalMode`: the resolved activation mode. `evacuate` resolves to
  `EVACUATING`; `shelter_in_place` resolves to `SHELTERING`; `avoid_area` and
  `follow_official_instruction` keep the requested mode but replace the primary
  action.
- `movementDirective`: one of `none`, `evacuate`, `shelter_in_place`,
  `avoid_area`, or `follow_official_instruction`.
- `primaryAction` / `primaryActionKind`: the CTA copy and machine-readable UI
  route. Current action kinds are `meet`, `stay`, `prepare`, `shelter`,
  `evacuate`, `avoid`, `official`, `recover`, and `normal`.
- `suppressedAction` / `suppressedReason`: why ordinary saved-plan navigation is
  secondary while official movement guidance is active.

An expired, cancelled, ended, or superseded governing alert cannot continue to
override the activation. Missing governing-alert context also fails closed to
`movementDirective = none`, even if a stale directive string exists on the row.

## Map Preview Behavior

Map previews must identify what they are previewing: planned meeting place, active destination, checked-in person, official hazard area, public resource, or unverified public place. A preview cannot use the same visual state for planned and confirmed locations.

## Drawer Behavior

Alert polygons, soft warning areas, and urgent hazard markers should open the same alert-info drawer. Switching household/community/group scopes should also be a drawer interaction, not a floating menu with different semantics.
