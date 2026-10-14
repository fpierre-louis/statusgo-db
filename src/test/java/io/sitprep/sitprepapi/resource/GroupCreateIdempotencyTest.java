package io.sitprep.sitprepapi.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.IdempotencyKey;
import io.sitprep.sitprepapi.dto.CreateGroupRequest;
import io.sitprep.sitprepapi.repo.IdempotencyKeyRepo;
import io.sitprep.sitprepapi.service.GroupService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B1 review: the server mints a group's id, so the client id no longer dedupes
 * a replayed create. The FE sends create through the offline outbox, which
 * replays the same body with one stable Idempotency-Key after a timeout or a
 * 5xx — possibly after the first attempt committed. The key must make that
 * replay return the first group, not make a second one.
 */
class GroupCreateIdempotencyTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aReplayedCreateWithTheSameKey_returnsTheFirstGroup_andCreatesOnce() throws Exception {
        GroupService service = mock(GroupService.class);
        when(service.createGroup(any(CreateGroupRequest.class), anyString())).thenAnswer(i -> {
            Group g = new Group();
            g.setGroupId(UUID.randomUUID().toString()); // a fresh id per call, as the server mints
            g.setGroupName("Grace Church");
            return g;
        });
        GroupResource resource = new GroupResource();
        ReflectionTestUtils.setField(resource, "groupService", service);

        // The filter resolves the handler through the real mapping, as in prod.
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.refresh();
        ctx.getBeanFactory().registerSingleton("groupResource", resource);
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(ctx);
        mapping.afterPropertiesSet();

        Map<IdempotencyKey.PK, IdempotencyKey> store = new HashMap<>();
        IdempotencyKeyRepo repo = mock(IdempotencyKeyRepo.class);
        when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(store.get(i.getArgument(0))));
        when(repo.save(any(IdempotencyKey.class))).thenAnswer(i -> {
            IdempotencyKey k = i.getArgument(0);
            store.put(new IdempotencyKey.PK(k.getCallerEmail(), k.getEndpoint(), k.getKey()), k);
            return k;
        });
        IdempotencyInterceptor filter = new IdempotencyInterceptor(repo, new ObjectMapper(),
                List.<HandlerMapping>of(mapping));
        ReflectionTestUtils.setField(filter, "ttlHours", 24L);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(resource).addFilters(filter).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "owner@x.com", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        String body = "{\"groupName\":\"Grace Church\",\"groupType\":\"Church\"}";
        String first = mvc.perform(post("/api/groups").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .header(IdempotencyInterceptor.HEADER, "k-1").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/groups").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .header(IdempotencyInterceptor.HEADER, "k-1").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ObjectMapper om = new ObjectMapper();
        assertThat(om.readTree(replay).get("groupId").asText())
                .isEqualTo(om.readTree(first).get("groupId").asText());
        verify(service, times(1)).createGroup(any(CreateGroupRequest.class), anyString());

        // A different key is a different create.
        mvc.perform(post("/api/groups").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .header(IdempotencyInterceptor.HEADER, "k-2").content(body))
                .andExpect(status().isOk());
        verify(service, times(2)).createGroup(any(CreateGroupRequest.class), anyString());
    }
}
