package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class HouseholdChallengesResourceTest {

    private static final String HOUSEHOLD = "hh-42";
    private static final String CALLER = "owner@example.com";

    private GroupRepo groupRepo;
    private HouseholdAccessService access;
    private HouseholdChallengesResource resource;

    @BeforeEach
    void setUp() {
        groupRepo = mock(GroupRepo.class);
        access = mock(HouseholdAccessService.class);
        resource = new HouseholdChallengesResource(groupRepo, access,
                org.mockito.Mockito.mock(io.sitprep.sitprepapi.gamification.TokenEventPublisher.class));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        CALLER, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void markCompleteStoresOnlyChallengeProgress() {
        Group household = household();
        when(groupRepo.findByGroupId(HOUSEHOLD)).thenReturn(Optional.of(household));

        var response = resource.markComplete(HOUSEHOLD, "2026-W35");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("2026-W35", true);
        assertThat(household.getChallengeProgress()).containsEntry("2026-W35", true);
        assertThat(household.getChallengeLastShownWeek()).isNull();
        verify(access).requireCanReadHousehold(CALLER, HOUSEHOLD);
        verify(groupRepo).save(household);
    }

    @Test
    void markShownDoesNotMarkChallengeComplete() {
        Group household = household();
        when(groupRepo.findByGroupId(HOUSEHOLD)).thenReturn(Optional.of(household));

        var response = resource.markShown(HOUSEHOLD, "2026-W35");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("challengeLastShownWeek", "2026-W35");
        assertThat(household.getChallengeLastShownWeek()).isEqualTo("2026-W35");
        assertThat(household.getChallengeProgress()).isEmpty();
        verify(access).requireCanReadHousehold(CALLER, HOUSEHOLD);
        verify(groupRepo).save(household);
    }

    @Test
    void markShownRejectsInvalidWeekKeysBeforeLookup() {
        var ex = assertThrows(ResponseStatusException.class,
                () -> resource.markShown(HOUSEHOLD, "2026-W99"));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(access, groupRepo);
    }

    private static Group household() {
        Group g = new Group();
        g.setGroupId(HOUSEHOLD);
        g.setGroupType("Household");
        g.setChallengeProgress(new HashMap<>());
        return g;
    }
}
