package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto.*;
import io.sitprep.sitprepapi.dto.PostSummaryDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GroupViewService {

    private static final int DTO_VERSION = 1;
    private static final int RECENT_POST_LIMIT = 5;

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final PostRepo postRepo;

    public GroupViewService(GroupRepo groupRepo, UserInfoRepo userInfoRepo, PostRepo postRepo) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.postRepo = postRepo;
    }

    @Transactional(readOnly = true)
    public Optional<GroupMemberViewDto> buildMemberView(String groupId, String viewerEmail) {
        if (groupId == null || groupId.isBlank()) return Optional.empty();
        return groupRepo.findByGroupId(groupId).map(g -> assemble(g, normalize(viewerEmail)));
    }

    private GroupMemberViewDto assemble(Group g, String viewerEmail) {
        List<String> memberEmails = g.getMemberEmails() == null ? List.of() : g.getMemberEmails();
        Map<String, UserInfo> byEmail = memberEmails.isEmpty()
                ? Map.of()
                : userInfoRepo.findByUserEmailIn(memberEmails).stream()
                        .collect(Collectors.toMap(u -> normalize(u.getUserEmail()), u -> u, (a, b) -> a));

        List<MemberSummary> members = memberEmails.stream()
                .map(email -> toMemberSummary(email, byEmail.get(normalize(email))))
                .toList();

        List<PostSummaryDto> recentPosts = postRepo.findPostsByGroupId(g.getGroupId()).stream()
                .limit(RECENT_POST_LIMIT)
                .map(p -> toPostSummary(p, byEmail))
                .toList();

        return new GroupMemberViewDto(
                toGroupInfo(g),
                resolveViewerRole(g, viewerEmail),
                members,
                recentPosts,
                new MetaDto(Instant.now(), DTO_VERSION)
        );
    }

    private GroupInfo toGroupInfo(Group g) {
        int memberCount = g.getMemberCount() != null ? g.getMemberCount()
                : (g.getMemberEmails() == null ? 0 : g.getMemberEmails().size());
        return new GroupInfo(
                g.getGroupId(),
                g.getGroupName(),
                g.getGroupType(),
                g.getDescription(),
                g.getAddress(),
                g.getLatitude(),
                g.getLongitude(),
                g.getZipCode(),
                memberCount,
                g.getAlert(),
                g.getCreatedAt(),
                g.getUpdatedAt(),
                g.getPrivacy(),
                g.getGroupCode(),
                g.getOwnerName(),
                g.getOwnerEmail(),
                g.getAdminEmails() == null ? List.of() : List.copyOf(g.getAdminEmails()),
                g.getSubGroupIDs() == null ? List.of() : List.copyOf(g.getSubGroupIDs())
        );
    }

    private MemberSummary toMemberSummary(String email, UserInfo u) {
        if (u == null) {
            return new MemberSummary(normalize(email), null, null, null, null);
        }
        SelfStatus status = new SelfStatus(
                u.getUserStatus(), u.getStatusColor(), u.getUserStatusLastUpdated()
        );
        return new MemberSummary(
                normalize(u.getUserEmail()),
                u.getUserFirstName(),
                u.getUserLastName(),
                u.getProfileImageURL(),
                status
        );
    }

    private PostSummaryDto toPostSummary(Post p, Map<String, UserInfo> byEmail) {
        UserInfo u = p.getAuthor() == null ? null : byEmail.get(normalize(p.getAuthor()));
        PostSummaryDto dto = new PostSummaryDto();
        dto.setId(p.getId());
        dto.setGroupId(p.getGroupId());
        dto.setGroupName(p.getGroupName());
        dto.setAuthor(p.getAuthor());
        if (u != null) {
            dto.setAuthorFirstName(u.getUserFirstName());
            dto.setAuthorLastName(u.getUserLastName());
            dto.setAuthorProfileImageURL(u.getProfileImageURL());
        }
        dto.setContent(p.getContent());
        dto.setTimestamp(p.getTimestamp());
        return dto;
    }

    private String resolveViewerRole(Group g, String viewerEmail) {
        if (viewerEmail == null || viewerEmail.isBlank()) return "none";
        if (viewerEmail.equalsIgnoreCase(g.getOwnerEmail())) return "owner";
        if (g.getAdminEmails() != null && g.getAdminEmails().stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(viewerEmail))) return "admin";
        if (g.getMemberEmails() != null && g.getMemberEmails().stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(viewerEmail))) return "member";
        return "none";
    }

    private static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
