package io.sitprep.sitprepapi.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.IdempotencyKey;
import io.sitprep.sitprepapi.dto.GroupPostCommentDto;
import io.sitprep.sitprepapi.repo.IdempotencyKeyRepo;
import io.sitprep.sitprepapi.service.GroupPostCommentService;
import io.sitprep.sitprepapi.service.ThreadAccessService;
import io.sitprep.sitprepapi.web.FakeIdempotencyRepo;
import io.sitprep.sitprepapi.web.IdempotencyInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F7 review 2: group comments go through the FE's offline outbox, which
 * replays the same body with one stable Idempotency-Key after a network error
 * or a 5xx, possibly after the first attempt committed. The key must make
 * that replay return the first comment, not create a second one (and send the
 * post's author a second notification).
 */
class GroupPostCommentIdempotencyTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aReplayedCommentWithTheSameKey_returnsTheFirstComment_andCreatesOnce() throws Exception {
        GroupPostCommentService service = mock(GroupPostCommentService.class);
        AtomicLong ids = new AtomicLong(100);
        when(service.createCommentFromDto(any(GroupPostCommentDto.class))).thenAnswer(i -> {
            GroupPostCommentDto in = i.getArgument(0);
            GroupPostCommentDto out = new GroupPostCommentDto();
            out.setId(ids.incrementAndGet()); // a fresh row per call, as the database would
            out.setPostId(in.getPostId());
            out.setContent(in.getContent());
            out.setTempId(in.getTempId());
            out.setAuthor(in.getAuthor());
            return out;
        });
        GroupPostCommentResource resource =
                new GroupPostCommentResource(service, mock(ThreadAccessService.class));

        // The filter resolves the handler through the real mapping, as in prod.
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.refresh();
        ctx.getBeanFactory().registerSingleton("groupPostCommentResource", resource);
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(ctx);
        mapping.afterPropertiesSet();

        IdempotencyKeyRepo repo = FakeIdempotencyRepo.create();
        IdempotencyInterceptor filter = new IdempotencyInterceptor(repo, new ObjectMapper(),
                List.<HandlerMapping>of(mapping));
        ReflectionTestUtils.setField(filter, "ttlHours", 24L);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(resource).addFilters(filter).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "member@x.com", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        String body = "{\"postId\":7,\"content\":\"On my way\",\"tempId\":\"cmnt_1\"}";
        String first = mvc.perform(post("/api/group-post-comments").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header(IdempotencyInterceptor.HEADER, "k-1").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/group-post-comments").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header(IdempotencyInterceptor.HEADER, "k-1").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ObjectMapper om = new ObjectMapper();
        assertThat(om.readTree(replay).get("id").asLong()).isEqualTo(om.readTree(first).get("id").asLong());
        assertThat(om.readTree(replay).get("tempId").asText()).isEqualTo("cmnt_1");
        verify(service, times(1)).createCommentFromDto(any(GroupPostCommentDto.class));

        // A different key is a different comment.
        mvc.perform(post("/api/group-post-comments").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header(IdempotencyInterceptor.HEADER, "k-2").content(body))
                .andExpect(status().isOk());
        verify(service, times(2)).createCommentFromDto(any(GroupPostCommentDto.class));
    }
}
