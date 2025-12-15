package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.RSMemberRole;
import io.sitprep.sitprepapi.domain.RSMemberStatus;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RSGroupMemberDto {

    // membership
    private String id;
    private String groupId;
    private String memberEmail;
    private RSMemberRole role;
    private RSMemberStatus status;
    private String invitedByEmail;
    private Instant joinedAt;
    private Instant createdAt;
    private Instant updatedAt;

    // user_info (joined by email)
    private String firebaseUid;
    private String userFirstName;
    private String userLastName;
    private String profileImageURL;
}