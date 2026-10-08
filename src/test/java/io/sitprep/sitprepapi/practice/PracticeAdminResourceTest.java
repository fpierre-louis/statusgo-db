package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.constant.PlatformRole;
import io.sitprep.sitprepapi.domain.PlatformAdmin;
import io.sitprep.sitprepapi.repo.PlatformAdminRepo;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static io.sitprep.sitprepapi.practice.PracticeFixtures.approve;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.scenario;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The kill switch is an operator control: 401 without a session, 403 without
 * MODERATE_REPORTS, and the break-glass token works. Uses the real
 * {@link PlatformAccessService} over a mocked admin table.
 */
class PracticeAdminResourceTest {

    private static final String MOD = "mod@example.com";
    private static final String USER = "neighbor@example.com";
    private static final String BREAK_GLASS = "s3cret-token";

    private PracticeContentControlRepo controls;
    private PracticeAdminResource resource;

    @BeforeEach
    void setUp() {
        PlatformAdminRepo admins = mock(PlatformAdminRepo.class);
        PlatformAdmin moderator = new PlatformAdmin();
        moderator.setEmail(MOD);
        moderator.setRole(PlatformRole.ADMIN); // ADMIN carries MODERATE_REPORTS
        when(admins.findByEmailIgnoreCaseAndActiveTrue(MOD)).thenReturn(Optional.of(moderator));
        when(admins.findByEmailIgnoreCaseAndActiveTrue(USER)).thenReturn(Optional.empty());

        controls = mock(PracticeContentControlRepo.class);
        when(controls.findAll()).thenReturn(List.of());
        when(controls.findById(any())).thenReturn(Optional.empty());
        when(controls.save(any())).thenAnswer(i -> i.getArgument(0));

        PracticeCatalog catalog = PracticeCatalog.of(List.of(version(approve(scenario("live", 1), PublishState.PUBLISHED))));
        PracticeAvailabilityService availability = new PracticeAvailabilityService(catalog, controls, true, false);
        resource = new PracticeAdminResource(availability, new PlatformAccessService(admins, BREAK_GLASS));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "n/a", List.of()));
    }

    private static HttpStatus statusOf(Runnable call) {
        try {
            call.run();
            return HttpStatus.OK;
        } catch (ResponseStatusException e) {
            return HttpStatus.valueOf(e.getStatusCode().value());
        }
    }

    @Test
    void unauthenticatedIs401() {
        assertThat(statusOf(() -> resource.status(null))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOf(() -> resource.disable("live", new PracticeAdminResource.DisableRequest("x"), null)))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ordinaryUserIs403AndNothingIsWritten() {
        signIn(USER);
        assertThat(statusOf(() -> resource.status(null))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> resource.disable("live", new PracticeAdminResource.DisableRequest("x"), null)))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> resource.enable("live", null))).isEqualTo(HttpStatus.FORBIDDEN);
        verify(controls, never()).save(any());
    }

    @Test
    void moderatorDisablesAndReEnables() {
        signIn(MOD);
        var off = resource.disable("live", new PracticeAdminResource.DisableRequest("  wording under review "), null).getBody();
        assertThat(off.disabled()).isTrue();
        assertThat(off.disabledReason()).isEqualTo("wording under review");
        assertThat(off.updatedBy()).isEqualTo(MOD);

        PracticeContentControl saved = new PracticeContentControl();
        saved.setContentKey("live");
        saved.setDisabledAt(off.disabledAt());
        saved.setDisabledReason(off.disabledReason());
        when(controls.findById("live")).thenReturn(Optional.of(saved));
        var on = resource.enable("live", null).getBody();
        assertThat(on.disabled()).isFalse();
    }

    @Test
    void breakGlassTokenWorksWithoutASession() {
        var body = resource.status(BREAK_GLASS).getBody();
        assertThat(body.practiceEnabled()).isTrue();
        assertThat(body.content()).singleElement().satisfies(c -> assertThat(c.key()).isEqualTo("live"));
    }

    @Test
    void disableNeedsAReasonAndAWellFormedKey() {
        signIn(MOD);
        assertThatThrownBy(() -> resource.disable("live", new PracticeAdminResource.DisableRequest("  "), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> resource.disable("../etc", new PracticeAdminResource.DisableRequest("x"), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }
}
