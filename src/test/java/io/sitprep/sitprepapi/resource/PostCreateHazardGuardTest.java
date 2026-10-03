package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.repo.TaskAssigneeRepo;
import io.sitprep.sitprepapi.service.GroupService;
import io.sitprep.sitprepapi.service.PostService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A hazard is created only through POST /api/hazards (its guards + the
 * hazard_report row). The public post create must refuse kind "hazard"
 * (hazard composer audit G1, 2026-10-03).
 */
class PostCreateHazardGuardTest {

    private PostService tasks;
    private PostResource resource;

    @BeforeEach
    void setUp() {
        tasks = mock(PostService.class);
        resource = new PostResource(tasks, mock(GroupService.class), mock(TaskAssigneeRepo.class), null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("me@x.com", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void refusesAHazardThroughThePublicCreate() {
        Post p = new Post();
        p.setKind(" Hazard ");
        p.setTitle("Flooding");
        assertThrows(IllegalArgumentException.class, () -> resource.create(p));
        verify(tasks, never()).create(any(), anyString());
    }

    @Test
    void stillCreatesOrdinaryPosts() {
        Post p = new Post();
        p.setKind("post");
        p.setDescription("hello");
        resource.create(p);
        verify(tasks).create(p, "me@x.com");
    }
}
