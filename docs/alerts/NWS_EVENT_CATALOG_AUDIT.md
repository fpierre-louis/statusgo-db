# NWS Event Catalog Audit

Date: 2026-08-27

Primary source checked: `https://api.weather.gov/alerts/types`.

The current response was saved to
`src/test/resources/fixtures/nws-alert-types-2026-08-27.json` so exact product
name coverage can be validated without a network call in tests.

## Findings

All configured exact NWS event names in `alert-dispatch-templates.json` appear
in the current NWS alert-types catalog.

Items called out for extra attention:

- `Extreme Fire Danger`: current NWS event type.
- `Flash Flood Watch`: current NWS event type. Some NWS products have changed
  over time, so this remains watch-tier/prepare-mode and should be revisited if
  the product is retired from the catalog.
- `Blowing Dust Warning`: current NWS event type.
- `Dense Smoke Advisory`: current NWS event type.
- `Storm Watch`: current NWS event type, but product semantics are often marine
  or non-tropical. It remains separate from tropical cyclone watches.
- `Storm Warning`: current NWS event type, but product semantics are often
  marine or non-tropical. It remains official-only/attention unless future
  household relevance rules are added.
- `Earthquake Warning`: current NWS event type. SitPrep keeps it distinct from
  USGS magnitude-only earthquake records.
- `Volcano Warning`: current NWS event type. SitPrep keeps it official-only
  until hazard subtype/action semantics are available.

## Limitations

The NWS event catalog verifies product names, not impact severity. Impact
severity lives on individual alert payloads under `properties.parameters`.

Live API sample checked 2026-08-27:

- Severe Thunderstorm Warning high-impact extension: `thunderstormDamageThreat`
  with trusted escalation values `CONSIDERABLE` and `DESTRUCTIVE`.
- Flash Flood Warning high-impact extension: `flashFloodDamageThreat` with
  trusted escalation values `CONSIDERABLE` and `CATASTROPHIC`.
- Snow Squall Warning high-impact extensions: `snowSquallImpact=SIGNIFICANT`
  and `WEAHandling=WEA`.
- Flood Warning sample parameters included `AWIPSidentifier`, `WMOidentifier`,
  `NWSheadline`, `BLOCKCHANNEL`, `EAS-ORG`, `VTEC`, `eventEndingTime`, and
  `expiredReferences`, but no documented/live damage-threat equivalent.

The backend now preserves NWS `properties.parameters` as
`NormalizedAlert.parameters`, and `AlertSafetyPolicy` owns escalation. CAP
severity/urgency/certainty and response type alone do not escalate
impact-aware weather warnings.

PAGER, MMI, and ShakeMap are still not normalized for USGS earthquake records.
