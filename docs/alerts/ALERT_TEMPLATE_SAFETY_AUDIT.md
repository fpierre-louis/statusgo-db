# Alert Template Safety Audit

Date: 2026-08-27

Template file: `src/main/resources/templates/alert-dispatch-templates.json`

## Counts

- Template objects: 52
- Step strings: 156
- NWS templates: 48
- USGS templates: 1
- FEMA templates: 3
- Warning-tier templates: 35
- Watch-tier templates: 17
- Templates with evidence metadata: 52
- Evidence metadata items: 84
- Templates explicitly blocked: 4
- Templates with safety-review metadata: 52
- Templates with response compatibility metadata: 52
- Templates with movement-directive metadata: 52
- Impact-aware templates: 4
- Templates marked human-approved: 48
- Templates still source-verified-only: 0
- Templates containing placeholders: 1 (`USGS` earthquake body uses `{mag}` and `{place}`)

## Confirmed Defects And Risk Areas

- Corrected: Tornado Warning and Extreme Wind Warning now have separate templates.
- Corrected: Extreme Wind Warning no longer uses basement or lowest-floor
  guidance borrowed from tornado treatment.
- Corrected: Extreme Wind Warning now carries product-specific NWS provenance
  for the Extreme Wind Warning / WEA product in addition to generic high-wind
  safety guidance.
- Corrected: Tornado wording no longer says a tornado was spotted.
- Corrected: Severe Thunderstorm Warning no longer assumes both damaging wind
  and hail and no longer asks users to unplug during an active storm.
- Corrected: Flash Flood Statement and Flood Statement no longer share
  act-now warning copy.
- Corrected: Storm Warning no longer shares Tropical Storm Warning copy.
- Corrected: Snow Squall Warning now has short-fused travel-specific handling.
- Corrected: Air Quality Alert no longer shares Dense Smoke Advisory copy or
  `askTag: Wildfire`.
- Corrected: Tsunami Watch and Tsunami Advisory now have different action levels.
- Corrected: civil, law-enforcement, hazmat, nuclear, and radiological products
  are individually reviewed. The civil/law rows are explicitly blocked; hazmat,
  nuclear, and radiological rows are official-only.
- Corrected: Tornado Warning and Tornado Watch no longer use `askTag: Hurricane`.
- Corrected: Severe Thunderstorm Warning, Flash Flood Warning, Flood Warning,
  and Snow Squall Warning default below critical push. Severe Thunderstorm,
  Flash Flood, and Snow Squall now escalate only from real NWS
  `properties.parameters` impact fields; Flood Warning stays lower/default
  until an equivalent trustworthy field exists.
- Corrected: Extreme Cold Watch is split from Freeze Watch / Freeze Warning.
  Freeze Warning remains prepare-mode despite its event name.
- Corrected: USGS magnitude-only earthquake copy now says reported nearby, not
  felt nearby, and remains attention-mode pending PAGER/MMI/ShakeMap impact
  data.

## Safety Metadata Gap

Every production template now carries:

- structured protective action,
- compatible CAP response types,
- incompatible CAP response types,
- explicit SitPrep dispatch mode,
- explicit SitPrep guidance mode,
- explicit movement-directive mode,
- impact-aware escalation metadata,
- authoritative evidence or an explicit blocked reason,
- source-verification timestamps,
- nullable human-approval timestamps,
- human safety-review status.

Evidence `supports[]` mappings were tightened so product-definition/catalog
sources support product metadata, not action copy; PAGER supports future impact
normalization, not current magnitude-only action copy; FEMA declaration pages
support assistance/channel/county checks separately from repair-record steps;
and CDC/NRC/EPA sources support only the hazmat/radiological fields they
actually describe.

Runtime can match a template and explain why SitPrep copy remains suppressed.
The 48 source-verified production templates were marked human-approved on
2026-08-27 at explicit user direction. The four blocked civil/law templates
remain unapproved and official-only. Any future `source_verified` template still
renders official-only until it receives explicit approval.

## Wording Change Policy

Do not rewrite all 114 instructions as part of schema work. Change copy only for confirmed defects, required splits, or source-backed corrections. Every wording change belongs in `ALERT_REFACTOR_EXECUTION_LOG.md`.
