package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.CreateGroupRequest;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JN-01: create trusts nothing but the creator's choices.
 *
 * <p>The JSON below is the shape the frontend's CreateGroup.js sends today: a
 * whole group, roster and all. It must still bind (no 400), and everything the
 * server owns must come out of the server, not the body.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupCreateTrustTest {

    @Mock GroupRepo groupRepo;
    @Mock UserInfoRepo userInfoRepo;
    @Mock WebSocketMessageSender ws;
    @Mock HouseholdEventService householdEventService;
    @Mock NotificationService notificationService;
    @Mock CheckInRequestService checkInRequestService;

    private GroupService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new GroupService(groupRepo, userInfoRepo, ws, householdEventService,
                notificationService, checkInRequestService);
        when(groupRepo.save(any(Group.class))).thenAnswer(i -> i.getArgument(0));
        when(groupRepo.existsById(anyString())).thenReturn(false);
        when(groupRepo.existsByGroupCodeIgnoreCase(anyString())).thenReturn(false);
        UserInfo me = new UserInfo();
        me.setUserEmail("creator@x.com");
        me.setUserFirstName("Cara");
        me.setUserLastName("Creator");
        when(userInfoRepo.findByUserEmailIgnoreCase("creator@x.com")).thenReturn(Optional.of(me));
    }

    /** CreateGroup.js's body, plus the fields an attacker would add. */
    private static final String FE_BODY = """
            {
              "groupId": "client-uuid-1",
              "version": 7,
              "groupName": "  Maple Street HOA  ",
              "groupType": "HOA/Neighborhood",
              "privacy": "Public",
              "description": "Our block",
              "address": "1 Maple St",
              "zipCode": "84101",
              "latitude": 40.76,
              "longitude": -111.89,
              "alert": "Active",
              "adminEmails": ["creator@x.com", "stranger@x.com"],
              "memberEmails": ["creator@x.com", "victim1@x.com", "victim2@x.com"],
              "pendingMemberEmails": ["p@x.com"],
              "ownerEmail": "someone-else@x.com",
              "ownerName": "Not Me",
              "memberCount": 40,
              "subGroupIDs": ["other-group"],
              "parentGroupIDs": ["agency-1"],
              "groupCode": "g-chosen",
              "planTier": "PREMIUM_AGENCY",
              "maxSeats": 100000,
              "stripeCustomerId": "cus_fake",
              "subscriptionOverrideTier": "AGENCY",
              "agencyAuthorized": true,
              "lastUpdatedBy": "someone-else@x.com",
              "createdAt": "2020-01-01T00:00:00Z",
              "logoImageUrl": "https://img/logo.png",
              "businessCategory": null,
              "websiteUrl": null
            }
            """;

    @Test
    void frontendBodyStillBinds_andOnlyTheCallerIsOnTheRoster() throws Exception {
        CreateGroupRequest req = mapper.readValue(FE_BODY, CreateGroupRequest.class);

        Group out = service.createGroup(req, "Creator@X.com");

        ArgumentCaptor<Group> saved = ArgumentCaptor.forClass(Group.class);
        verify(groupRepo).save(saved.capture());
        Group g = saved.getValue();
        assertThat(out).isSameAs(g);
        // The creator's choices.
        assertThat(g.getGroupName()).isEqualTo("Maple Street HOA");
        assertThat(g.getGroupType()).isEqualTo("HOA/Neighborhood");
        assertThat(g.getPrivacy()).isEqualTo("Public");
        assertThat(g.getDescription()).isEqualTo("Our block");
        assertThat(g.getAddress()).isEqualTo("1 Maple St");
        assertThat(g.getZipCode()).isEqualTo("84101");
        assertThat(g.getLatitude()).isEqualTo(40.76);
        assertThat(g.getLogoImageUrl()).isEqualTo("https://img/logo.png");
        // The server's.
        assertThat(g.getGroupId()).isNotEqualTo("client-uuid-1").hasSize(36);
        assertThat(g.getVersion()).isNull();
        assertThat(g.getGroupCode()).startsWith("g-").isNotEqualTo("g-chosen");
        assertThat(g.getOwnerEmail()).isEqualTo("creator@x.com");
        assertThat(g.getOwnerName()).isEqualTo("Cara Creator");
        assertThat(g.getAdminEmails()).containsExactly("creator@x.com");
        assertThat(g.getMemberEmails()).containsExactly("creator@x.com");
        assertThat(g.getPendingMemberEmails()).isEmpty();
        assertThat(g.getMemberCount()).isEqualTo(1);
        assertThat(g.getSubGroupIDs()).isEmpty();
        assertThat(g.getParentGroupIDs()).isEmpty();
        assertThat(g.getAlert()).isEqualTo("Not Active");
        assertThat(g.getPlanTier()).isEqualTo("FREE");
        assertThat(g.getMaxSeats()).isNull();
        assertThat(g.getStripeCustomerId()).isNull();
        assertThat(g.getSubscriptionOverrideTier()).isNull();
        assertThat(g.isAgencyAuthorized()).isFalse();
        assertThat(g.getLastUpdatedBy()).isEqualTo("creator@x.com");
        assertThat(g.getCreatedAt()).isAfter(java.time.Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void existingIdPlusVersion_is409_andNothingIsWritten() throws Exception {
        when(groupRepo.existsById("client-uuid-1")).thenReturn(true);
        CreateGroupRequest req = mapper.readValue(FE_BODY, CreateGroupRequest.class);

        assertThatThrownBy(() -> service.createGroup(req, "creator@x.com"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(groupRepo, never()).save(any());
    }

    @Test
    void household_isAlwaysPrivate_withAnHHCode_andSeedsTheBase() throws Exception {
        CreateGroupRequest req = new CreateGroupRequest(null, "The Smiths", "household", "Public",
                null, null, null, null, null, null, null, null);
        UserInfo me = userInfoRepo.findByUserEmailIgnoreCase("creator@x.com").orElseThrow();

        Group g = service.createGroup(req, "creator@x.com");

        assertThat(g.getGroupType()).isEqualTo("Household");
        assertThat(g.getPrivacy()).isEqualTo("Private");
        assertThat(g.getGroupCode()).startsWith("HH-");
        assertThat(me.getBaseHouseholdId()).isEqualTo(g.getGroupId());
    }

    @Test
    void blankNameOrType_is400() {
        for (CreateGroupRequest bad : new CreateGroupRequest[]{
                new CreateGroupRequest(null, "  ", "Church", null, null, null, null, null, null, null, null, null),
                new CreateGroupRequest(null, "Grace", null, null, null, null, null, null, null, null, null, null)}) {
            assertThatThrownBy(() -> service.createGroup(bad, "creator@x.com"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verify(groupRepo, never()).save(any());
    }
}
