// src/main/java/io/sitprep/sitprepapi/resource/PublicPlanResource.java
package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.*;
import io.sitprep.sitprepapi.dto.PublicPlanResponse;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/public")
public class PublicPlanResource {

    private final MeetingPlaceService meetingPlaceService;
    private final EvacuationPlanService evacuationPlanService;
    private final OriginLocationService originLocationService;
    private final EmergencyContactGroupService emergencyContactGroupService;

    private final UserInfoRepo userInfoRepo;

    public PublicPlanResource(
            MeetingPlaceService meetingPlaceService,
            EvacuationPlanService evacuationPlanService,
            OriginLocationService originLocationService,
            EmergencyContactGroupService emergencyContactGroupService,
            UserInfoRepo userInfoRepo
    ) {
        this.meetingPlaceService = meetingPlaceService;
        this.evacuationPlanService = evacuationPlanService;
        this.originLocationService = originLocationService;
        this.emergencyContactGroupService = emergencyContactGroupService;
        this.userInfoRepo = userInfoRepo;
    }

    /**
     * Public, read-only snapshot of a household's deployed plan.
     * Returns only deploy=true items for the given email.
     */
    @GetMapping("/deployed-plan")
    public ResponseEntity<PublicPlanResponse> getDeployedPlan(@RequestParam("email") String email) {
        if (!StringUtils.hasText(email)) return ResponseEntity.badRequest().build();
        final String ownerEmail = email.trim().toLowerCase();

        // 1) User summary (from UserInfo only; Demographic has no name/phone)
        PublicPlanResponse.UserSummary user = new PublicPlanResponse.UserSummary();
        userInfoRepo.findByUserEmailIgnoreCase(ownerEmail).ifPresent(ui -> {
            user.setFirstName(nz(ui.getUserFirstName()));
            user.setLastName(nz(ui.getUserLastName()));
            user.setPhone(nz(ui.getPhone()));
            // Optional: pick a timestamp you like; here we use userStatusLastUpdated
            user.setLastUpdated(ui.getUserStatusLastUpdated());
        });

        // 2) Meeting places (deploy=true)
        List<MeetingPlace> meetingPlaces = meetingPlaceService.getMeetingPlacesByOwnerEmail(ownerEmail)
                .stream().filter(MeetingPlace::isDeploy).toList();

        // 3) Evacuation plans (deploy=true)
        List<EvacuationPlan> evacuationPlans = evacuationPlanService.getEvacuationPlansByOwner(ownerEmail)
                .stream().filter(EvacuationPlan::isDeploy).toList();

        // 4) Origin locations (no deploy flag—return all)
        List<OriginLocation> origins = originLocationService.getByOwnerEmail(ownerEmail);

        // 5) Emergency contact groups by owner
        List<EmergencyContactGroup> groups = emergencyContactGroupService.getGroupsByOwnerEmail(ownerEmail);

        // Build DTO
        PublicPlanResponse resp = new PublicPlanResponse();
        resp.setUser(user);

        resp.setMeetingPlaces(meetingPlaces.stream().map(mp -> {
            PublicPlanResponse.MeetingPlaceDTO dto = new PublicPlanResponse.MeetingPlaceDTO();
            dto.setId(mp.getId());
            dto.setName(mp.getName());
            dto.setAddress(mp.getAddress());
            dto.setPhoneNumber(mp.getPhoneNumber());
            dto.setAdditionalInfo(mp.getAdditionalInfo());
            dto.setLat(mp.getLat());
            dto.setLng(mp.getLng());
            return dto;
        }).toList());

        resp.setEvacuationPlans(evacuationPlans.stream().map(ep -> {
            PublicPlanResponse.EvacuationPlanDTO dto = new PublicPlanResponse.EvacuationPlanDTO();
            dto.setId(ep.getId());
            dto.setShelterName(ep.getShelterName());
            dto.setShelterAddress(ep.getShelterAddress());
            dto.setShelterPhoneNumber(ep.getShelterPhoneNumber());
            dto.setLat(ep.getLat());
            dto.setLng(ep.getLng());
            dto.setTravelMode(ep.getTravelMode());
            dto.setShelterInfo(ep.getShelterInfo());
            return dto;
        }).toList());

        resp.setOriginLocations(origins.stream().map(o -> {
            PublicPlanResponse.OriginLocationDTO dto = new PublicPlanResponse.OriginLocationDTO();
            dto.setId(o.getId());
            dto.setName(o.getName());
            dto.setAddress(o.getAddress());
            dto.setLat(o.getLat());
            dto.setLng(o.getLng());
            return dto;
        }).toList());

        resp.setEmergencyContactGroups(groups.stream().map(g -> {
            PublicPlanResponse.EmergencyContactGroupDTO gdto = new PublicPlanResponse.EmergencyContactGroupDTO();
            gdto.setId(g.getId());
            gdto.setName(g.getName());
            gdto.setContacts(
                    g.getContacts().stream().map(c -> {
                        PublicPlanResponse.EmergencyContactDTO cdto = new PublicPlanResponse.EmergencyContactDTO();
                        cdto.setId(c.getId());
                        cdto.setName(c.getName());
                        cdto.setRole(c.getRole());
                        cdto.setPhone(c.getPhone());
                        cdto.setEmail(c.getEmail());
                        cdto.setAddress(c.getAddress());
                        cdto.setRadioChannel(c.getRadioChannel());
                        cdto.setMedicalInfo(c.getMedicalInfo());
                        return cdto;
                    }).toList()
            );
            return gdto;
        }).toList());

        return ResponseEntity.ok(resp);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
