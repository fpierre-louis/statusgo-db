package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.dto.EmergencyContactGroupDto;
import io.sitprep.sitprepapi.service.EmergencyContactGroupService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/emergency-groups")
@CrossOrigin(origins = "http://localhost:3000")
public class EmergencyContactGroupResource {

    private final EmergencyContactGroupService groupService;
    private final HouseholdAccessService access;

    public EmergencyContactGroupResource(EmergencyContactGroupService groupService,
                                         HouseholdAccessService access) {
        this.groupService = groupService;
        this.access = access;
    }

    // GET /api/emergency-groups (dump-all) was DELETED 2026-08-24.
    //
    // It required only a signed-in caller, and returned every emergency contact
    // of every user in the database — name, phone, home address, and
    // `medicalInfo`, which the contact form invites free-text health detail into
    // ("Allergic to penicillin"). One request, no id, whole table. The comment
    // that used to sit here reasoned that auth-only was "still better than fully
    // open"; with `/api/**` on permitAll (SecurityConfig:38-52) a free Firebase
    // signup was the entire cost of entry.
    //
    // Do not re-add a dump route here. If a platform admin needs a cross-tenant
    // read, the shape already exists one file over: UserInfoResource:80-81 gates
    // on PlatformPermission.VIEW_PII *and* writes an AdminAuditLog record.

    /**
     * Fetch a user's contact groups. Household plan-sharing has members
     * reading the household head's contacts, so the {@code ownerEmail}
     * param can target a different user — but only if the caller shares
     * a household with them (or is the owner themselves). Otherwise 403.
     */
    @GetMapping("/owner")
    public List<EmergencyContactGroupDto> getGroupsByOwnerEmail(@RequestParam String ownerEmail) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadPlanDataFor(caller, ownerEmail);
        return groupService.getGroupsByOwnerEmail(ownerEmail)
                .stream().map(EmergencyContactGroupDto::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmergencyContactGroupDto> getGroupById(@PathVariable Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<EmergencyContactGroup> opt = groupService.getGroupById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        // 404 (not 403) when the caller can't see this group — don't leak
        // which ids exist in someone else's household.
        EmergencyContactGroup g = opt.get();
        if (!access.canReadPlanDataFor(caller, g.getOwnerEmail())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(EmergencyContactGroupDto.from(g));
    }

    @PostMapping
    public ResponseEntity<EmergencyContactGroupDto> createGroup(@RequestBody EmergencyContactGroup group) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        group.setOwnerEmail(caller); // override body
        EmergencyContactGroup saved = groupService.createGroup(group);
        return ResponseEntity.ok(EmergencyContactGroupDto.from(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmergencyContactGroupDto> updateGroup(
            @PathVariable Long id,
            @RequestBody EmergencyContactGroup updatedGroup
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        ensureOwns(id, caller);
        // The author stays the author: updateGroup never copies ownerEmail.
        EmergencyContactGroup saved = groupService.updateGroup(id, updatedGroup);
        return ResponseEntity.ok(EmergencyContactGroupDto.from(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        ensureOwns(id, caller);
        groupService.deleteGroup(id);
        return ResponseEntity.noContent().build();
    }

    private void ensureOwns(Long id, String caller) {
        Optional<EmergencyContactGroup> existing = groupService.getGroupById(id);
        if (existing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // A household's contacts are part of its shared plan (household-owned
        // plans, 2026-05-22): the author, or any owner/admin of the household
        // the group belongs to, may change it, the same rule as choosing the
        // Main meeting spot. Plain members still change only their own.
        String owner = existing.get().getOwnerEmail();
        boolean author = owner != null && owner.equalsIgnoreCase(caller);
        String hid = existing.get().getHouseholdId();
        boolean admin = hid != null && access.canWriteHousehold(caller, hid);
        if (!author && !admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the household's admins can change these contacts");
        }
    }
}
