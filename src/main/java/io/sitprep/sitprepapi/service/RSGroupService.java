package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.RSGroup;
import io.sitprep.sitprepapi.domain.RSGroupMember;
import io.sitprep.sitprepapi.domain.RSMemberRole;
import io.sitprep.sitprepapi.domain.RSMemberStatus;
import io.sitprep.sitprepapi.dto.RSGroupMemberDto;
import io.sitprep.sitprepapi.dto.RSGroupMemberMapper;
import io.sitprep.sitprepapi.repo.RSGroupMemberRepo;
import io.sitprep.sitprepapi.repo.RSGroupRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class RSGroupService {

    private final RSGroupRepo groupRepo;
    private final RSGroupMemberRepo memberRepo;

    public RSGroupService(RSGroupRepo groupRepo, RSGroupMemberRepo memberRepo) {
        this.groupRepo = groupRepo;
        this.memberRepo = memberRepo;
    }

    // --------------------------
    // Groups
    // --------------------------

    public List<RSGroup> getPublicGroups() {
        return groupRepo.findByIsPrivateFalseOrderByCreatedAtDesc();
    }

    public Optional<RSGroup> getById(String id) {
        return groupRepo.findById(id);
    }

    public List<RSGroup> getGroupsForCurrentUserOrEmailFallback(String emailFallback) {
        String email = resolveEmail(emailFallback);
        if (email == null || email.isBlank()) return List.of();

        List<String> ids = memberRepo.findActiveGroupIdsForEmail(email, RSMemberStatus.ACTIVE);
        if (ids.isEmpty()) return List.of();

        // For MVP: OK if order is not guaranteed by DB. We sort by createdAt desc.
        List<RSGroup> groups = groupRepo.findAllById(ids);
        groups.sort(Comparator.comparing(RSGroup::getCreatedAt).reversed());
        return groups;
    }

    // --------------------------
    // Members (DTO-returning)
    // --------------------------

    public List<RSGroupMemberDto> getMembers(String groupId) {
        return memberRepo.findByGroupIdWithUserInfo(groupId)
                .stream()
                .map(RSGroupMemberMapper::toDto)
                .toList();
    }

    // --------------------------
    // Create / Update / Delete Group
    // --------------------------

    @Transactional
    public RSGroup createGroup(RSGroup incoming, String emailFallback) {
        String actor = mustResolveEmail(emailFallback);

        RSGroup g = new RSGroup();
        g.setName(requireNonBlank(incoming.getName(), "name"));
        g.setSportType(requireNonBlank(incoming.getSportType(), "sportType"));
        g.setDescription(incoming.getDescription());
        g.setPrivate(incoming.isPrivate());
        g.setOwnerEmail(actor);
        g.setCreatedAt(Instant.now());
        g.setUpdatedAt(Instant.now());

        RSGroup saved = groupRepo.save(g);

        // Owner membership row (ACTIVE immediately)
        RSGroupMember ownerMember = new RSGroupMember();
        ownerMember.setGroupId(saved.getId());
        ownerMember.setMemberEmail(actor);
        ownerMember.setRole(RSMemberRole.OWNER);
        ownerMember.setStatus(RSMemberStatus.ACTIVE);
        ownerMember.setInvitedByEmail(actor);
        ownerMember.setJoinedAt(Instant.now());
        ownerMember.setCreatedAt(Instant.now());
        ownerMember.setUpdatedAt(Instant.now());
        memberRepo.save(ownerMember);

        return saved;
    }

    @Transactional
    public RSGroup updateGroup(String id, RSGroup incoming, String emailFallback) {
        RSGroup g = groupRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("RSGroup not found: " + id));
        String actor = mustResolveEmail(emailFallback);

        requireOwnerOrAdmin(id, actor);

        if (incoming.getName() != null && !incoming.getName().isBlank()) g.setName(incoming.getName().trim());
        if (incoming.getSportType() != null && !incoming.getSportType().isBlank()) g.setSportType(incoming.getSportType().trim());
        if (incoming.getDescription() != null) g.setDescription(incoming.getDescription());
        g.setPrivate(incoming.isPrivate());

        g.setUpdatedAt(Instant.now());
        return groupRepo.save(g);
    }

    @Transactional
    public void deleteGroup(String groupId, String emailFallback) {
        String actor = mustResolveEmail(emailFallback);
        requireOwner(groupId, actor);

        // delete memberships first (safe for MVP)
        List<RSGroupMember> members = memberRepo.findByGroupIdOrderByCreatedAtAsc(groupId);
        memberRepo.deleteAll(members);

        groupRepo.deleteById(groupId);
    }

    // --------------------------
    // Membership flows (DTO-returning)
    // --------------------------

    /** Invite creates/updates membership as PENDING (owner/admin only) */
    @Transactional
    public RSGroupMemberDto inviteMember(String groupId, String memberEmail, String emailFallback) {
        String actor = mustResolveEmail(emailFallback);
        requireOwnerOrAdmin(groupId, actor);

        String target = normalize(memberEmail);
        if (target == null || target.isBlank()) throw new IllegalArgumentException("memberEmail required");

        RSGroupMember m = memberRepo.findOneWithUserInfo(groupId, target)
                .orElseGet(() -> {
                    RSGroupMember created = new RSGroupMember();
                    created.setGroupId(groupId);
                    created.setMemberEmail(target);
                    created.setCreatedAt(Instant.now());
                    return created;
                });

        if (m.getRole() == null) m.setRole(RSMemberRole.MEMBER);

        m.setStatus(RSMemberStatus.PENDING);
        m.setInvitedByEmail(actor);
        m.setUpdatedAt(Instant.now());

        memberRepo.save(m);

        // Re-fetch with userInfo join so DTO includes names/image when available
        RSGroupMember hydrated = memberRepo.findOneWithUserInfo(groupId, target).orElse(m);
        return RSGroupMemberMapper.toDto(hydrated);
    }

    /** Approve flips PENDING -> ACTIVE (owner/admin only) */
    @Transactional
    public RSGroupMemberDto approveMember(String groupId, String memberEmail, String emailFallback) {
        String actor = mustResolveEmail(emailFallback);
        requireOwnerOrAdmin(groupId, actor);

        String target = normalize(memberEmail);
        RSGroupMember m = memberRepo.findByGroupIdAndMemberEmailIgnoreCase(groupId, target)
                .orElseThrow(() -> new IllegalArgumentException("Membership not found"));

        m.setStatus(RSMemberStatus.ACTIVE);
        m.setJoinedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        memberRepo.save(m);

        RSGroupMember hydrated = memberRepo.findOneWithUserInfo(groupId, target).orElse(m);
        return RSGroupMemberMapper.toDto(hydrated);
    }

    /** Self-join for PUBLIC groups: creates ACTIVE membership immediately */
    @Transactional
    public RSGroupMemberDto joinPublicGroup(String groupId, String emailFallback) {
        String actor = mustResolveEmail(emailFallback);

        RSGroup g = groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("RSGroup not found: " + groupId));
        if (g.isPrivate()) throw new IllegalArgumentException("This group is private. Invitation required.");

        RSGroupMember m = memberRepo.findByGroupIdAndMemberEmailIgnoreCase(groupId, actor)
                .orElseGet(() -> {
                    RSGroupMember created = new RSGroupMember();
                    created.setGroupId(groupId);
                    created.setMemberEmail(actor);
                    created.setCreatedAt(Instant.now());
                    created.setRole(RSMemberRole.MEMBER);
                    return created;
                });

        m.setStatus(RSMemberStatus.ACTIVE);
        if (m.getJoinedAt() == null) m.setJoinedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        memberRepo.save(m);

        RSGroupMember hydrated = memberRepo.findOneWithUserInfo(groupId, actor).orElse(m);
        return RSGroupMemberMapper.toDto(hydrated);
    }

    /** Remove member (owner/admin). Owner can’t be removed. */
    @Transactional
    public void removeMember(String groupId, String memberEmail, String emailFallback) {
        String actor = mustResolveEmail(emailFallback);
        requireOwnerOrAdmin(groupId, actor);

        RSGroup group = groupRepo.findById(groupId).orElseThrow(() -> new IllegalArgumentException("RSGroup not found: " + groupId));
        String target = normalize(memberEmail);
        if (target == null || target.isBlank()) throw new IllegalArgumentException("memberEmail required");

        if (target.equalsIgnoreCase(normalize(group.getOwnerEmail()))) {
            throw new IllegalArgumentException("Owner cannot be removed.");
        }

        RSGroupMember m = memberRepo.findByGroupIdAndMemberEmailIgnoreCase(groupId, target)
                .orElseThrow(() -> new IllegalArgumentException("Membership not found"));

        m.setStatus(RSMemberStatus.REMOVED);
        m.setUpdatedAt(Instant.now());
        memberRepo.save(m);
    }

    /** Promote/demote (owner only) */
    @Transactional
    public RSGroupMemberDto setRole(String groupId, String memberEmail, RSMemberRole role, String emailFallback) {
        String actor = mustResolveEmail(emailFallback);
        requireOwner(groupId, actor);

        RSGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("RSGroup not found: " + groupId));

        String target = normalize(memberEmail);
        if (target == null || target.isBlank()) throw new IllegalArgumentException("memberEmail required");

        RSGroupMember m = memberRepo.findByGroupIdAndMemberEmailIgnoreCase(groupId, target)
                .orElseThrow(() -> new IllegalArgumentException("Membership not found"));

        // Owner role is locked to the group's ownerEmail
        if (normalize(group.getOwnerEmail()).equalsIgnoreCase(target)) {
            m.setRole(RSMemberRole.OWNER);
        } else {
            m.setRole(role == null ? RSMemberRole.MEMBER : role);
        }

        m.setUpdatedAt(Instant.now());
        memberRepo.save(m);

        RSGroupMember hydrated = memberRepo.findOneWithUserInfo(groupId, target).orElse(m);
        return RSGroupMemberMapper.toDto(hydrated);
    }

    // --------------------------
    // Permission helpers
    // --------------------------

    public boolean isActiveMember(String groupId, String email) {
        return memberRepo.findByGroupIdAndMemberEmailIgnoreCase(groupId, email)
                .map(m -> m.getStatus() == RSMemberStatus.ACTIVE)
                .orElse(false);
    }

    public boolean isOwnerOrAdmin(String groupId, String email) {
        return memberRepo.findByGroupIdAndMemberEmailIgnoreCase(groupId, email)
                .map(m -> m.getStatus() == RSMemberStatus.ACTIVE
                        && (m.getRole() == RSMemberRole.OWNER || m.getRole() == RSMemberRole.ADMIN))
                .orElse(false);
    }

    public void requireOwnerOrAdmin(String groupId, String email) {
        if (!isOwnerOrAdmin(groupId, email)) throw new IllegalArgumentException("Owner/admin permission required.");
    }

    public void requireOwner(String groupId, String email) {
        RSGroup g = groupRepo.findById(groupId).orElseThrow(() -> new IllegalArgumentException("RSGroup not found: " + groupId));
        if (!normalize(g.getOwnerEmail()).equalsIgnoreCase(normalize(email))) {
            throw new IllegalArgumentException("Only the owner can perform this action.");
        }
    }

    // --------------------------
    // Email helpers
    // --------------------------

    private String resolveEmail(String fallback) {
        try {
            String fromAuth = AuthUtils.getCurrentUserEmail();
            if (fromAuth != null && !fromAuth.isBlank()) return normalize(fromAuth);
        } catch (Exception ignored) {}
        return normalize(fallback);
    }

    private String mustResolveEmail(String fallback) {
        String e = resolveEmail(fallback);
        if (e == null || e.isBlank()) throw new IllegalArgumentException("User email required (auth or request).");
        return e;
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}