package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.RSGroupMember;
import io.sitprep.sitprepapi.domain.UserInfo;

public class RSGroupMemberMapper {

    private RSGroupMemberMapper() {}

    public static RSGroupMemberDto toDto(RSGroupMember m) {
        if (m == null) return null;

        UserInfo u = null;
        try {
            u = m.getUserInfo(); // safe because we’ll fetch-join in repo
        } catch (Exception ignored) {}

        return RSGroupMemberDto.builder()
                .id(m.getId())
                .groupId(m.getGroupId())
                .memberEmail(m.getMemberEmail())
                .role(m.getRole())
                .status(m.getStatus())
                .invitedByEmail(m.getInvitedByEmail())
                .joinedAt(m.getJoinedAt())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())

                .firebaseUid(u != null ? u.getFirebaseUid() : null)
                .userFirstName(u != null ? u.getUserFirstName() : null)
                .userLastName(u != null ? u.getUserLastName() : null)
                .profileImageURL(u != null ? u.getProfileImageURL() : null)
                .build();
    }
}