# Alert Template Human Review Matrix

Date: 2026-08-27

This file is generated from `src/main/resources/templates/alert-dispatch-templates.json` for Pass 2B review parity. It is not an approval record. Production display of SitPrep-authored guidance still requires `safetyReview.status = approved`, and this agent marked none approved.

Summary: 52 production templates; 48 source-verified; 4 blocked; 0 human-approved.

## 1. Tornado Warning

Source: NWS

Current headline: Tornado warning

Current body: A tornado warning is in effect. Get to a basement or inside room away from windows now.

Steps:
- Go to a basement or inside room on the lowest floor.
- Stay away from windows and protect your head.
- Stay sheltered until the warning ends.

Protective action: SHELTER

Movement directive: none

Dispatch mode: critical_push

Guidance mode: supplement_official

Compatible CAP response types: Shelter, Execute

Incompatible CAP response types: Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; What to Do During a Tornado; https://www.weather.gov/safety/tornado-during; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.
## 2. Extreme Wind Warning

Source: NWS

Current headline: Extreme wind warning

Current body: Extreme wind is expected or happening. Get inside a sturdy building and stay away from windows.

Steps:
- Go inside a sturdy building now.
- Move to an interior room away from windows.
- Stay away from downed power lines.

Protective action: SHELTER

Movement directive: none

Dispatch mode: critical_push

Guidance mode: supplement_official

Compatible CAP response types: Shelter, Execute

Incompatible CAP response types: Evacuate, AllClear

Safety review: source_verified; version 3; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Extreme Wind Warning Wireless Emergency Alert; https://www.weather.gov/wrn/wea360; checked 2026-08-27; supports eventAny, sitprep.dispatchMode
- NOAA / National Weather Service; During a High Wind Event; https://www.weather.gov/safety/wind-during; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 3. Severe Thunderstorm Warning

Source: NWS

Current headline: Severe thunderstorm warning

Current body: A severe storm warning is in effect. Get inside, stay away from windows, and avoid travel.

Steps:
- Go inside a sturdy building.
- Stay away from windows and large open rooms.
- Do not drive unless officials tell you to move.

Protective action: SHELTER

Movement directive: none

Dispatch mode: attention (impact-aware)

Guidance mode: supplement_official

Compatible CAP response types: Shelter, Avoid, Execute

Incompatible CAP response types: Evacuate, AllClear

Safety review: source_verified; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; What to Do During Severe Weather; https://www.weather.gov/safety/thunderstorm-during; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 4. Flash Flood Warning

Source: NWS

Current headline: Flash flood warning

Current body: Flash flooding is imminent or happening. Move to higher ground and avoid flood water.

Steps:
- Move to higher ground if you are in a flood-prone place.
- Never walk or drive through flood water.
- Follow evacuation orders immediately.

Protective action: AVOID

Movement directive: none

Dispatch mode: attention (impact-aware)

Guidance mode: supplement_official

Compatible CAP response types: Avoid, Evacuate, Execute

Incompatible CAP response types: Shelter, AllClear

Safety review: source_verified; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; During a Flood; https://www.weather.gov/safety/flood-during; checked 2026-08-27; supports body, steps[0], steps[2]
- NOAA / National Weather Service; Turn Around Don't Drown; https://www.weather.gov/safety/flood-turn-around-dont-drown; checked 2026-08-27; supports steps[1]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 5. Flash Flood Statement

Source: NWS

Current headline: Flash flood update

Current body: Officials updated a flash flood alert. Read the official update before changing plans.

Steps:
- Check what changed in the official update.
- Avoid flooded roads and low crossings.
- Stay ready to move if officials tell you to.

Protective action: MONITOR

Movement directive: none

Dispatch mode: attention

Guidance mode: supplement_official

Compatible CAP response types: Monitor, Avoid

Incompatible CAP response types: Shelter, Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; During a Flood; https://www.weather.gov/safety/flood-during; checked 2026-08-27; supports body, steps[0], steps[2]
- NOAA / National Weather Service; Turn Around Don't Drown; https://www.weather.gov/safety/flood-turn-around-dont-drown; checked 2026-08-27; supports steps[1]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 6. Flood Warning

Source: NWS

Current headline: Flood warning

Current body: Flooding is happening or expected. Avoid flood water and be ready to leave if water rises.

Steps:
- Move valuables above possible water if there is time.
- Never drive through a flooded road.
- Leave immediately if officials tell you to evacuate.

Protective action: AVOID

Movement directive: none

Dispatch mode: attention (impact-aware)

Guidance mode: supplement_official

Compatible CAP response types: Avoid, Evacuate, Monitor

Incompatible CAP response types: Shelter, AllClear

Safety review: source_verified; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; During a Flood; https://www.weather.gov/safety/flood-during; checked 2026-08-27; supports body, steps[0], steps[2]
- NOAA / National Weather Service; Turn Around Don't Drown; https://www.weather.gov/safety/flood-turn-around-dont-drown; checked 2026-08-27; supports steps[1]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 7. Flood Statement

Source: NWS

Current headline: Flood update

Current body: Officials updated a flood alert. Read the update and keep avoiding flood water.

Steps:
- Check the official update for timing and roads.
- Stay away from flooded roads and basements.
- Keep listening in case the warning changes.

Protective action: MONITOR

Movement directive: none

Dispatch mode: attention

Guidance mode: supplement_official

Compatible CAP response types: Monitor, Avoid

Incompatible CAP response types: Shelter, Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; During a Flood; https://www.weather.gov/safety/flood-during; checked 2026-08-27; supports body, steps[0], steps[2]
- NOAA / National Weather Service; Turn Around Don't Drown; https://www.weather.gov/safety/flood-turn-around-dont-drown; checked 2026-08-27; supports steps[1]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 8. Hurricane Warning / Typhoon Warning

Source: NWS

Current headline: Hurricane warning

Current body: Hurricane conditions are expected. Follow evacuation orders; otherwise stay inside away from windows.

Steps:
- Leave now if officials told your area to evacuate.
- If staying, move to an inside room away from windows.
- Charge phones and fill water containers while you can.

Protective action: PREPARE

Movement directive: none

Dispatch mode: critical_push

Guidance mode: supplement_official

Compatible CAP response types: Prepare, Evacuate, Shelter

Incompatible CAP response types: AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Hurricane Safety; https://www.weather.gov/safety/hurricane; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]
- FEMA / Ready.gov; Evacuation; https://www.ready.gov/evacuation; checked 2026-08-27; supports protectiveAction, sitprep.movementDirective, steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 9. Tropical Storm Warning

Source: NWS

Current headline: Tropical storm warning

Current body: Tropical storm conditions are expected. Secure loose items, charge phones, and avoid travel.

Steps:
- Bring in loose outdoor items.
- Charge phones and check flashlights.
- Stay off roads once winds or flooding begin.

Protective action: PREPARE

Movement directive: none

Dispatch mode: attention

Guidance mode: supplement_official

Compatible CAP response types: Prepare, Avoid, Monitor

Incompatible CAP response types: Shelter, Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Hurricane Safety; https://www.weather.gov/safety/hurricane; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]
- NOAA / National Weather Service; During a High Wind Event; https://www.weather.gov/safety/wind-during; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 10. Storm Warning

Source: NWS

Current headline: Storm warning

Current body: Dangerous storm-force winds are expected over water. Check the official alert before travel.

Steps:
- Avoid boating or shoreline travel in the warned area.
- Secure loose outdoor items if winds reach land.
- Follow local officials and marine warnings.

Protective action: AVOID

Movement directive: none

Dispatch mode: attention

Guidance mode: official_only

Compatible CAP response types: Avoid, Monitor

Incompatible CAP response types: Shelter, Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Wind Warnings, Watches and Advisories; https://www.weather.gov/safety/wind-ww; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 11. Blizzard Warning

Source: NWS

Current headline: Blizzard warning

Current body: Travel may become dangerous or impossible. Stay put and keep heat, food, and water close.

Steps:
- Do not travel unless officials say you must.
- Keep heat, water, food, and medicine in one reachable place.
- Check on nearby people who may not have heat.

Protective action: SHELTER

Movement directive: none

Dispatch mode: critical_push

Guidance mode: supplement_official

Compatible CAP response types: Shelter, Avoid

Incompatible CAP response types: Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Winter Weather Warnings, Watches and Advisories; https://www.weather.gov/safety/winter-ww; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 12. Winter Storm Warning / Ice Storm Warning / Lake Effect Snow Warning

Source: NWS

Current headline: Winter storm warning

Current body: Heavy snow or ice is expected. Delay travel and get ready for power problems.

Steps:
- Delay travel until conditions improve.
- Keep a charged phone, blanket, and water nearby.
- Find flashlights before power problems begin.

Protective action: AVOID

Movement directive: none

Dispatch mode: attention

Guidance mode: supplement_official

Compatible CAP response types: Avoid, Prepare, Monitor

Incompatible CAP response types: Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Winter Weather Warnings, Watches and Advisories; https://www.weather.gov/safety/winter-ww; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 13. Snow Squall Warning

Source: NWS

Current headline: Snow squall warning

Current body: A short burst of snow can make roads suddenly unsafe. Avoid or delay driving now.

Steps:
- Avoid or delay driving until the squall passes.
- If already driving, slow down and turn on headlights.
- Leave extra space and do not slam on brakes.

Protective action: AVOID

Movement directive: none

Dispatch mode: attention (impact-aware)

Guidance mode: supplement_official

Compatible CAP response types: Avoid, Execute

Incompatible CAP response types: Shelter, Evacuate, AllClear

Safety review: source_verified; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Snow Squall; https://www.weather.gov/safety/winter-snow-squall; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 14. Extreme Cold Warning

Source: NWS

Current headline: Dangerous cold

Current body: Cold can injure exposed skin quickly. Stay inside, cover skin if you go out, and check heat.

Steps:
- Stay inside if you can.
- Cover exposed skin before going out.
- Check on people nearby who may not have heat.

Protective action: SHELTER

Movement directive: none

Dispatch mode: attention

Guidance mode: supplement_official

Compatible CAP response types: Shelter, Avoid

Incompatible CAP response types: Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; During Extremely Cold Weather; https://www.weather.gov/safety/cold-during; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 15. Extreme Heat Warning

Source: NWS

Current headline: Dangerous heat

Current body: Heat can make you sick fast. Get to a cool place, drink water, and avoid hard outdoor work.

Steps:
- Stay in air conditioning or shade during the hottest hours.
- Drink water before you feel thirsty.
- Never leave people or pets in a parked car.

Protective action: SHELTER

Movement directive: none

Dispatch mode: attention

Guidance mode: supplement_official

Compatible CAP response types: Shelter, Avoid

Incompatible CAP response types: Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Heat Safety; https://www.weather.gov/safety/heat; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 16. Red Flag Warning / Extreme Fire Danger

Source: NWS

Current headline: Fire danger is high

Current body: Fire weather is critical. Avoid sparks and be ready for official evacuation updates.

Steps:
- Do not burn or create sparks outside.
- Keep vehicles off dry grass.
- Review your evacuation plan and keep your phone reachable.

Protective action: AVOID

Movement directive: none

Dispatch mode: attention

Guidance mode: supplement_official

Compatible CAP response types: Avoid, Prepare, Monitor

Incompatible CAP response types: Shelter, Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Understanding Wildfire Warnings, Watches and Behavior; https://www.weather.gov/safety/wildfire-ww; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]
- NOAA / National Weather Service; Wildfire Hazards; https://www.weather.gov/safety/wildfire-hazards; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 17. Fire Warning

Source: NWS

Current headline: Fire warning

Current body: A fire emergency is reported near the warned area. Follow official instructions immediately.

Steps:
- Leave now if officials tell your area to evacuate.
- If not told to leave, stay alert for updates.
- Take medicine, pets, and your go bag if you evacuate.

Protective action: HAZARD_SPECIFIC

Movement directive: none

Dispatch mode: critical_push

Guidance mode: official_only

Compatible CAP response types: Evacuate, Prepare, Monitor, Avoid

Incompatible CAP response types: Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Wildfire Hazards; https://www.weather.gov/safety/wildfire-hazards; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]
- FEMA / Ready.gov; Evacuation; https://www.ready.gov/evacuation; checked 2026-08-27; supports protectiveAction, sitprep.movementDirective, steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 18. High Wind Warning

Source: NWS

Current headline: High wind warning

Current body: Strong winds are happening or expected. Get inside and stay away from trees and power lines.

Steps:
- Go inside a sturdy building.
- Stay away from trees and downed power lines.
- If driving, slow down and watch high-profile vehicles.

Protective action: SHELTER

Movement directive: none

Dispatch mode: attention

Guidance mode: supplement_official

Compatible CAP response types: Shelter, Avoid

Incompatible CAP response types: Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; During a High Wind Event; https://www.weather.gov/safety/wind-during; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]
- NOAA / National Weather Service; Wind Warnings, Watches and Advisories; https://www.weather.gov/safety/wind-ww; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 19. Dust Storm Warning / Blowing Dust Warning

Source: NWS

Current headline: Dust storm warning

Current body: Visibility may drop suddenly. If driving, pull off the road and keep brake lights off.

Steps:
- Pull as far off the road as you safely can.
- Stop, set the brake, and turn lights off.
- Stay inside with windows closed if you are not driving.

Protective action: AVOID

Movement directive: none

Dispatch mode: critical_push

Guidance mode: supplement_official

Compatible CAP response types: Avoid, Execute

Incompatible CAP response types: Shelter, Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; During a High Wind Event; https://www.weather.gov/safety/wind-during; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 20. Avalanche Warning

Source: NWS

Current headline: Avalanche warning

Current body: Avalanche danger is high. Avoid avalanche terrain and the slopes below it.

Steps:
- Avoid avalanche terrain today.
- Stay out from below steep slopes and runout paths.
- Check your local avalanche center before any mountain travel.

Protective action: AVOID

Movement directive: none

Dispatch mode: attention

Guidance mode: supplement_official

Compatible CAP response types: Avoid, Execute

Incompatible CAP response types: Shelter, Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Avalanche Safety; https://www.weather.gov/safety/winter-avalanche; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 21. Tsunami Warning

Source: NWS

Current headline: Tsunami warning

Current body: Dangerous coastal flooding may happen. Move to high ground or inland if you are in a hazard zone.

Steps:
- Move inland or to high ground if you are near the water.
- Stay out of the water and away from beaches.
- Stay away until officials say the danger has passed.

Protective action: EVACUATE

Movement directive: none

Dispatch mode: critical_push

Guidance mode: supplement_official

Compatible CAP response types: Evacuate, Execute

Incompatible CAP response types: Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; During a Tsunami; https://www.weather.gov/safety/tsunami-during; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]
- NOAA / National Weather Service; Tsunami Hazards; https://www.weather.gov/safety/tsunami-hazards; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 22. Volcano Warning

Source: NWS

Current headline: Volcano warning

Current body: A volcano hazard alert is active. Follow official instructions; ash may require staying inside.

Steps:
- Follow evacuation or shelter instructions from officials.
- If ash is falling, close doors, windows, and vents.
- Avoid driving in ash unless you must.

Protective action: HAZARD_SPECIFIC

Movement directive: none

Dispatch mode: attention

Guidance mode: official_only

Compatible CAP response types: Evacuate, Shelter, Avoid, Monitor

Incompatible CAP response types: AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Volcanic Ash and Ashfall; https://www.weather.gov/safety/airquality-volcanic-ash; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]
- U.S. Geological Survey; Volcanic Ash Impacts and Mitigation; https://volcanoes.usgs.gov/volcanic_ash/; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 23. Earthquake Warning

Source: NWS

Current headline: Earthquake warning

Current body: Shaking may be starting or continuing. Drop, cover, and hold on.

Steps:
- Drop, cover, and hold on.
- Stay away from windows and outside walls.
- After shaking stops, check for injuries and hazards.

Protective action: SHELTER

Movement directive: none

Dispatch mode: critical_push

Guidance mode: supplement_official

Compatible CAP response types: Shelter, Execute

Incompatible CAP response types: Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- U.S. Geological Survey; Earthquake Facts and Earthquake Fantasy; https://www.usgs.gov/programs/earthquake-hazards/earthquake-facts-earthquake-fantasy; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 24. Air Quality Alert

Source: NWS

Current headline: Air quality alert

Current body: Air quality may be unhealthy. Check the official alert and reduce heavy outdoor activity.

Steps:
- Check which pollutant and group the official alert names.
- Reduce long or heavy outdoor activity.
- Move activity indoors if the air bothers you.

Protective action: AVOID

Movement directive: none

Dispatch mode: feed

Guidance mode: official_only

Compatible CAP response types: Avoid, Monitor

Incompatible CAP response types: Shelter, Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- AirNow / U.S. EPA; Using the Air Quality Index; https://www.airnow.gov/aqi/aqi-basics/using-air-quality-index/; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 25. Dense Smoke Advisory

Source: NWS

Current headline: Dense smoke advisory

Current body: Smoke may make the air unhealthy. Stay indoors when you can and keep smoky air out.

Steps:
- Keep windows and doors closed when smoke is heavy.
- Set air systems to recirculate if you can.
- Use a well-fitting N95 if you must be outside in smoke.

Protective action: SHELTER

Movement directive: none

Dispatch mode: attention

Guidance mode: supplement_official

Compatible CAP response types: Shelter, Avoid, Monitor

Incompatible CAP response types: Evacuate, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- U.S. Environmental Protection Agency; Reduce Your Smoke Exposure; https://www.epa.gov/wildfires/reduce-your-smoke-exposure; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 26. Evacuation Immediate

Source: NWS

Current headline: Leave now

Current body: Officials are telling people in the warned area to leave now. Follow the route in the official alert.

Steps:
- Leave by the route officials name.
- Take medicine, IDs, pets, and your go bag.
- Tell your household where you are going when safe.

Protective action: EVACUATE

Movement directive: evacuate

Dispatch mode: critical_push

Guidance mode: supplement_official

Compatible CAP response types: Evacuate

Incompatible CAP response types: Shelter, Avoid, AllClear

Safety review: source_verified; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- FEMA / Ready.gov; Evacuation; https://www.ready.gov/evacuation; checked 2026-08-27; supports protectiveAction, sitprep.movementDirective, steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 27. Shelter In Place Warning

Source: NWS

Current headline: Shelter in place

Current body: Officials are telling people in the warned area to shelter in place. Go inside and follow the alert.

Steps:
- Go inside and bring people and pets with you.
- Close doors and windows.
- Follow the official alert for ventilation or sealing steps.

Protective action: SHELTER

Movement directive: shelter_in_place

Dispatch mode: critical_push

Guidance mode: supplement_official

Compatible CAP response types: Shelter

Incompatible CAP response types: Evacuate, Avoid, AllClear

Safety review: source_verified; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- FEMA / Ready.gov; Shelter; https://www.ready.gov/shelter; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 28. Civil Danger Warning

Source: NWS

Current headline: Civil danger warning

Current body: Officials sent a danger warning for your area. Read the official alert and do what it says.

Steps:
- Read the official instruction before acting.
- Avoid the affected area if officials say to.
- Keep your phone available for updates.

Protective action: HAZARD_SPECIFIC

Movement directive: follow_official_instruction

Dispatch mode: critical_push

Guidance mode: official_only

Compatible CAP response types: Avoid, Shelter, Evacuate, Monitor

Incompatible CAP response types: AllClear

Blocked reason: Civil danger warnings are intentionally broad; SitPrep cannot safely infer shelter, evacuation, or avoidance without issuer-specific instruction.

Safety review: blocked; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; NWS API Alert Types; https://api.weather.gov/alerts/types; checked 2026-08-27; supports eventAny
- NOAA / National Weather Service; National Weather Service Non-Weather Emergency Products Specification; https://www.weather.gov/directives/sym/pd01017013curr.pdf; checked 2026-08-27; supports eventAny, blockedReason

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 29. Local Area Emergency

Source: NWS

Current headline: Local emergency

Current body: Officials sent a local emergency alert. Read the official instruction before changing plans.

Steps:
- Read the official alert.
- Follow the action named by local officials.
- Keep listening for updates.

Protective action: HAZARD_SPECIFIC

Movement directive: follow_official_instruction

Dispatch mode: attention

Guidance mode: official_only

Compatible CAP response types: Avoid, Shelter, Evacuate, Monitor

Incompatible CAP response types: AllClear

Blocked reason: Local Area Emergency has no single protective action across jurisdictions.

Safety review: blocked; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; NWS API Alert Types; https://api.weather.gov/alerts/types; checked 2026-08-27; supports eventAny
- NOAA / National Weather Service; National Weather Service Non-Weather Emergency Products Specification; https://www.weather.gov/directives/sym/pd01017013curr.pdf; checked 2026-08-27; supports eventAny, blockedReason

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 30. Civil Emergency Message

Source: NWS

Current headline: Civil emergency message

Current body: Local officials sent an emergency message. Use the official text as the instruction.

Steps:
- Read the official message.
- Follow the official action exactly.
- Keep your phone available for updates.

Protective action: HAZARD_SPECIFIC

Movement directive: follow_official_instruction

Dispatch mode: attention

Guidance mode: official_only

Compatible CAP response types: Avoid, Shelter, Evacuate, Monitor

Incompatible CAP response types: AllClear

Blocked reason: Civil Emergency Message is a relay format, not a stable hazard/action template.

Safety review: blocked; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; NWS API Alert Types; https://api.weather.gov/alerts/types; checked 2026-08-27; supports eventAny
- NOAA / National Weather Service; National Weather Service Non-Weather Emergency Products Specification; https://www.weather.gov/directives/sym/pd01017013curr.pdf; checked 2026-08-27; supports eventAny, blockedReason

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 31. Law Enforcement Warning

Source: NWS

Current headline: Law enforcement warning

Current body: Law enforcement sent a warning for the area. Follow the official instruction exactly.

Steps:
- Read the official instruction before acting.
- Avoid the affected area unless told otherwise.
- Do not share unverified details.

Protective action: HAZARD_SPECIFIC

Movement directive: follow_official_instruction

Dispatch mode: critical_push

Guidance mode: official_only

Compatible CAP response types: Avoid, Shelter, Evacuate, Monitor

Incompatible CAP response types: AllClear

Blocked reason: Law enforcement instructions can conflict with generic movement or shelter advice.

Safety review: blocked; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; NWS API Alert Types; https://api.weather.gov/alerts/types; checked 2026-08-27; supports eventAny
- NOAA / National Weather Service; National Weather Service Non-Weather Emergency Products Specification; https://www.weather.gov/directives/sym/pd01017013curr.pdf; checked 2026-08-27; supports eventAny, blockedReason

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 32. Hazardous Materials Warning

Source: NWS

Current headline: Hazardous materials warning

Current body: A hazardous materials alert is active. Follow the official instruction; it may tell you to leave or shelter.

Steps:
- Follow the official alert before choosing to leave or shelter.
- If told to shelter, close windows, doors, and vents.
- If told to evacuate, leave by the route officials name.

Protective action: HAZARD_SPECIFIC

Movement directive: follow_official_instruction

Dispatch mode: critical_push

Guidance mode: official_only

Compatible CAP response types: Shelter, Evacuate, Avoid

Incompatible CAP response types: AllClear

Safety review: source_verified; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; NWS API Alert Types; https://api.weather.gov/alerts/types; checked 2026-08-27; supports eventAny
- NOAA / National Weather Service; National Weather Service Non-Weather Emergency Products Specification; https://www.weather.gov/directives/sym/pd01017013curr.pdf; checked 2026-08-27; supports eventAny
- Centers for Disease Control and Prevention; Shelter in Place for a Chemical Emergency; https://www.cdc.gov/chemical-emergencies/response/shelter-in-place.html; checked 2026-08-27; supports steps[1]
- Centers for Disease Control and Prevention; Evacuate in a Chemical Emergency; https://www.cdc.gov/chemical-emergencies/response/evacuation.html; checked 2026-08-27; supports steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 33. Nuclear Power Plant Warning

Source: NWS

Current headline: Nuclear plant warning

Current body: A nuclear plant alert is active. Follow official instructions before leaving or sheltering.

Steps:
- Read the official alert before acting.
- If told to stay inside, move to the middle of the building or basement.
- Stay tuned for official updates.

Protective action: HAZARD_SPECIFIC

Movement directive: follow_official_instruction

Dispatch mode: critical_push

Guidance mode: official_only

Compatible CAP response types: Shelter, Evacuate, Monitor

Incompatible CAP response types: AllClear

Safety review: source_verified; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; NWS API Alert Types; https://api.weather.gov/alerts/types; checked 2026-08-27; supports eventAny
- U.S. Nuclear Regulatory Commission; Emergency Preparedness and Response; https://www.nrc.gov/about-nrc/emerg-preparedness; checked 2026-08-27; supports body, steps[0], steps[2], sitprep.movementDirective
- U.S. Environmental Protection Agency; Radiation Emergencies and Preparedness; https://www.epa.gov/radtown/radiation-emergencies-and-preparedness; checked 2026-08-27; supports body, steps[1], steps[2], sitprep.movementDirective

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 34. Radiological Hazard Warning

Source: NWS

Current headline: Radiological hazard warning

Current body: A radiation hazard alert is active. Get official instructions and follow them exactly.

Steps:
- Read the official alert before acting.
- If told to shelter, get inside and move away from windows.
- Stay tuned until officials say it is safe.

Protective action: HAZARD_SPECIFIC

Movement directive: follow_official_instruction

Dispatch mode: critical_push

Guidance mode: official_only

Compatible CAP response types: Shelter, Evacuate, Monitor

Incompatible CAP response types: AllClear

Safety review: source_verified; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- U.S. Environmental Protection Agency; Radiation Emergencies and Preparedness; https://www.epa.gov/radtown/radiation-emergencies-and-preparedness; checked 2026-08-27; supports body, steps[1], steps[2], sitprep.movementDirective

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 35. Tornado Watch

Source: NWS

Current headline: Tornado watch

Current body: Tornadoes are possible. Pick your shelter room now and keep alerts where you can hear them.

Steps:
- Choose a basement or inside room away from windows.
- Keep your phone charged and alerts on.
- Be ready to move there fast if a warning is issued.

Protective action: PREPARE

Movement directive: none

Dispatch mode: prepare

Guidance mode: supplement_official

Compatible CAP response types: Prepare, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; What to Do During a Tornado; https://www.weather.gov/safety/tornado-during; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 36. Severe Thunderstorm Watch

Source: NWS

Current headline: Severe thunderstorm watch

Current body: Severe storms are possible. Plan to get inside quickly if a warning is issued.

Steps:
- Bring loose outdoor items inside.
- Charge your phone.
- Plan where you will go if the storm becomes severe.

Protective action: PREPARE

Movement directive: none

Dispatch mode: prepare

Guidance mode: supplement_official

Compatible CAP response types: Prepare, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; What to Do During Severe Weather; https://www.weather.gov/safety/thunderstorm-during; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 37. Flash Flood Watch / Flood Watch

Source: NWS

Current headline: Flood watch

Current body: Flooding is possible. Plan a route to higher ground and avoid low roads.

Steps:
- Plan how you would reach higher ground.
- Move vehicles away from low spots if you can.
- Keep checking for warnings or evacuation orders.

Protective action: PREPARE

Movement directive: none

Dispatch mode: prepare

Guidance mode: supplement_official

Compatible CAP response types: Prepare, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; During a Flood; https://www.weather.gov/safety/flood-during; checked 2026-08-27; supports body, steps[0], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 38. Hurricane Watch / Typhoon Watch / Tropical Storm Watch

Source: NWS

Current headline: Tropical cyclone watch

Current body: Tropical storm or hurricane conditions are possible. Review your plan and watch for evacuation updates.

Steps:
- Review where you would go if told to evacuate.
- Refill medicine, water, and fuel if you can.
- Secure outdoor items before winds arrive.

Protective action: PREPARE

Movement directive: none

Dispatch mode: prepare

Guidance mode: supplement_official

Compatible CAP response types: Prepare, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Hurricane Safety; https://www.weather.gov/safety/hurricane; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]
- FEMA / Ready.gov; Evacuation; https://www.ready.gov/evacuation; checked 2026-08-27; supports protectiveAction, sitprep.movementDirective, steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 39. Storm Watch

Source: NWS

Current headline: Storm watch

Current body: Storm-force winds are possible over water. Check the official alert before marine or coastal travel.

Steps:
- Avoid planning boat travel in the watch area.
- Check official marine and local updates.
- Secure loose items if strong winds may reach land.

Protective action: PREPARE

Movement directive: none

Dispatch mode: feed

Guidance mode: official_only

Compatible CAP response types: Prepare, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Wind Warnings, Watches and Advisories; https://www.weather.gov/safety/wind-ww; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 40. Winter Storm Watch

Source: NWS

Current headline: Winter storm watch

Current body: Heavy snow or ice is possible. Stock up before roads get difficult.

Steps:
- Get food, water, and medicine before roads worsen.
- Charge devices and find flashlights.
- Plan to stay put once it starts.

Protective action: PREPARE

Movement directive: none

Dispatch mode: prepare

Guidance mode: supplement_official

Compatible CAP response types: Prepare, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Winter Weather Warnings, Watches and Advisories; https://www.weather.gov/safety/winter-ww; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 41. Extreme Cold Watch

Source: NWS

Current headline: Extreme cold watch

Current body: Dangerous cold is possible. Plan how people will stay warm and limit time outside.

Steps:
- Find warm layers before the cold arrives.
- Check that heat works where people will sleep.
- Plan check-ins for anyone who may lose heat.

Protective action: PREPARE

Movement directive: none

Dispatch mode: prepare

Guidance mode: supplement_official

Compatible CAP response types: Prepare, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Prepare for Cold Weather; https://www.weather.gov/safety/cold-before; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 42. Freeze Watch / Freeze Warning

Source: NWS

Current headline: Freeze watch or warning

Current body: Freezing temperatures may damage pets, plants, pipes, or outdoor water systems.

Steps:
- Bring pets inside.
- Cover sensitive plants if there is time.
- Protect pipes and outdoor water lines.

Protective action: PREPARE

Movement directive: none

Dispatch mode: prepare

Guidance mode: supplement_official

Compatible CAP response types: Prepare, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Prepare for Cold Weather; https://www.weather.gov/safety/cold-before; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 43. Extreme Heat Watch

Source: NWS

Current headline: Dangerous heat coming

Current body: Dangerous heat is possible. Find a cool place and plan around the hottest hours.

Steps:
- Plan indoor time during the hottest hours.
- Stock water and check cooling options.
- Check on older adults and people without cooling.

Protective action: PREPARE

Movement directive: none

Dispatch mode: prepare

Guidance mode: supplement_official

Compatible CAP response types: Prepare, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Heat Safety; https://www.weather.gov/safety/heat; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 44. Fire Weather Watch

Source: NWS

Current headline: Fire weather watch

Current body: Critical fire weather is possible. Avoid sparks and review your evacuation plan.

Steps:
- Avoid outdoor burning or spark-producing work.
- Review two ways out of your area.
- Keep your phone available for official updates.

Protective action: PREPARE

Movement directive: none

Dispatch mode: prepare

Guidance mode: supplement_official

Compatible CAP response types: Prepare, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Understanding Wildfire Warnings, Watches and Behavior; https://www.weather.gov/safety/wildfire-ww; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 45. High Wind Watch

Source: NWS

Current headline: High wind watch

Current body: Strong winds are possible. Secure loose items and plan to avoid exposed places.

Steps:
- Bring in or tie down loose outdoor items.
- Avoid rooftop or ladder work.
- Charge devices in case power goes out.

Protective action: PREPARE

Movement directive: none

Dispatch mode: prepare

Guidance mode: supplement_official

Compatible CAP response types: Prepare, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Wind Warnings, Watches and Advisories; https://www.weather.gov/safety/wind-ww; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 46. Avalanche Watch

Source: NWS

Current headline: Avalanche watch

Current body: Avalanche danger may rise. Check the local forecast before going near steep slopes.

Steps:
- Check your local avalanche forecast.
- Change plans if steep terrain is mentioned.
- Carry beacon, shovel, and probe if you travel in avalanche terrain.

Protective action: PREPARE

Movement directive: none

Dispatch mode: prepare

Guidance mode: supplement_official

Compatible CAP response types: Prepare, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; Avalanche Safety; https://www.weather.gov/safety/winter-avalanche; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 47. Tsunami Watch

Source: NWS

Current headline: Tsunami watch

Current body: A distant tsunami is possible. Stay updated and be ready to act if the alert changes.

Steps:
- Get updates from official sources.
- Know your route to high ground if you are near the coast.
- Be ready to act if a warning is issued.

Protective action: PREPARE

Movement directive: none

Dispatch mode: prepare

Guidance mode: supplement_official

Compatible CAP response types: Prepare, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; During a Tsunami; https://www.weather.gov/safety/tsunami-during; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]
- NOAA / National Weather Service; Tsunami Hazards; https://www.weather.gov/safety/tsunami-hazards; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 48. Tsunami Advisory

Source: NWS

Current headline: Tsunami advisory

Current body: Strong currents or waves may be dangerous near water. Stay out of the water and off beaches.

Steps:
- Stay out of the water.
- Stay away from beaches and waterways.
- Follow local officials and keep checking updates.

Protective action: AVOID

Movement directive: none

Dispatch mode: attention

Guidance mode: supplement_official

Compatible CAP response types: Avoid, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- NOAA / National Weather Service; During a Tsunami; https://www.weather.gov/safety/tsunami-during; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]
- NOAA / National Weather Service; Tsunami Hazards; https://www.weather.gov/safety/tsunami-hazards; checked 2026-08-27; supports body, steps[0], steps[1], steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 49. USGS Earthquake reported nearby

Source: USGS

Current headline: Earthquake reported nearby

Current body: A magnitude {mag} earthquake was reported near {place}. Check people first, then check for hazards.

Steps:
- Check yourself and people nearby for injuries.
- Expect aftershocks; drop, cover, and hold on if shaking starts.
- If you smell gas, get outside before using switches.

Protective action: ASSESS

Movement directive: none

Dispatch mode: attention

Guidance mode: supplement_official

Compatible CAP response types: Assess, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 2; source-verified 2026-08-27; approved not approved

Evidence:
- Ready.gov; Earthquakes; https://www.ready.gov/earthquakes; checked 2026-08-27; supports steps[0], steps[1], steps[2]
- U.S. Geological Survey; Earthquake Facts and Earthquake Fantasy; https://www.usgs.gov/programs/earthquake-hazards/earthquake-facts-earthquake-fantasy; checked 2026-08-27; supports body
- U.S. Geological Survey; PAGER FAQ; https://earthquake.usgs.gov/data/pager/faq.php; checked 2026-08-27; supports futureImpactNormalization

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 50. FEMA Hurricane / Severe Storm / Severe Storm(s) / Coastal Storm

Source: FEMA

Current headline: FEMA disaster declared

Current body: FEMA declared a disaster in your area. You may be able to apply for official recovery help.

Steps:
- Use official FEMA or DisasterAssistance.gov channels.
- Save photos and receipts for damage and repairs.
- Check whether your county is listed for individual help.

Protective action: ASSESS

Movement directive: none

Dispatch mode: feed

Guidance mode: supplement_official

Compatible CAP response types: Assess, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- Federal Emergency Management Agency; Individual Assistance; https://www.fema.gov/assistance/individual; checked 2026-08-27; supports body, steps[0]
- Federal Emergency Management Agency; After Applying for Assistance; https://www.fema.gov/assistance/individual/after-applying; checked 2026-08-27; supports steps[1]
- Federal Emergency Management Agency; Designated Areas; https://www.fema.gov/disaster/declarations; checked 2026-08-27; supports steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 51. FEMA Fire

Source: FEMA

Current headline: FEMA wildfire disaster declared

Current body: FEMA declared a wildfire disaster in your area. Official recovery help may be available.

Steps:
- Use official FEMA or DisasterAssistance.gov channels.
- Save photos and receipts for damage and repairs.
- Check whether your county is listed for individual help.

Protective action: ASSESS

Movement directive: none

Dispatch mode: feed

Guidance mode: supplement_official

Compatible CAP response types: Assess, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- Federal Emergency Management Agency; Individual Assistance; https://www.fema.gov/assistance/individual; checked 2026-08-27; supports body, steps[0]
- Federal Emergency Management Agency; After Applying for Assistance; https://www.fema.gov/assistance/individual/after-applying; checked 2026-08-27; supports steps[1]
- Federal Emergency Management Agency; Designated Areas; https://www.fema.gov/disaster/declarations; checked 2026-08-27; supports steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.

## 52. FEMA fallback

Source: FEMA

Current headline: FEMA disaster declared

Current body: FEMA declared a disaster in your area. Check official channels for recovery details.

Steps:
- Use official FEMA or DisasterAssistance.gov channels.
- Save photos and receipts if you have damage.
- Check whether your county is listed for individual help.

Protective action: ASSESS

Movement directive: none

Dispatch mode: feed

Guidance mode: official_only

Compatible CAP response types: Assess, Monitor

Incompatible CAP response types: Evacuate, Shelter, AllClear

Safety review: source_verified; version 1; source-verified 2026-08-27; approved not approved

Evidence:
- Federal Emergency Management Agency; Individual Assistance; https://www.fema.gov/assistance/individual; checked 2026-08-27; supports body, steps[0]
- Federal Emergency Management Agency; After Applying for Assistance; https://www.fema.gov/assistance/individual/after-applying; checked 2026-08-27; supports steps[1]
- Federal Emergency Management Agency; Designated Areas; https://www.fema.gov/disaster/declarations; checked 2026-08-27; supports steps[2]

Human review checklist:
- [ ] Source supports the body claim where `supports[]` names `body`.
- [ ] Source supports each cited step where `supports[]` names that step.
- [ ] CAP compatibility and incompatibility are correct.
- [ ] Movement directive is not overstated.
- [ ] This template may be marked approved by a human reviewer.
