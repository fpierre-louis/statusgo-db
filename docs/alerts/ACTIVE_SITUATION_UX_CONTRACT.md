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

## Map Preview Behavior

Map previews must identify what they are previewing: planned meeting place, active destination, checked-in person, official hazard area, public resource, or unverified public place. A preview cannot use the same visual state for planned and confirmed locations.

## Drawer Behavior

Alert polygons, soft warning areas, and urgent hazard markers should open the same alert-info drawer. Switching household/community/group scopes should also be a drawer interaction, not a floating menu with different semantics.
