package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.sitprep.sitprepapi.domain.GroupPostReaction;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupPostReactionRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.GroupPostReactionService;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * FD-08 / PR-09 (G1, additive): a stable user id rides next to every member email
 * on the reaction roster and on the work-order requester and assignee. The email
 * fields stay (TestFlight build 9 reads them).
 */
class UserIdNextToEmailTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static UserInfo user(String id, String email) {
        UserInfo u = new UserInfo();
        u.setId(id);
        u.setUserEmail(email);
        u.setUserFirstName("F");
        u.setUserLastName("L");
        return u;
    }

    private static Post task() {
        Post p = new Post();
        p.setId(1L);
        p.setKind("task");
        p.setTitle("t");
        p.setRequesterEmail("req@x.com");
        p.setStatus(Post.PostStatus.OPEN);
        p.setPriority(Post.PostPriority.MEDIUM);
        p.setCreatedAt(Instant.parse("2026-10-13T10:00:00Z"));
        return p;
    }

    @Test
    void postDtoCarriesRequesterAndAssigneeUserIds_andKeepsTheEmails() throws Exception {
        PostDto dto = PostDto.fromEntity(task())
                .withAuthor(user("u-req", "req@x.com"))
                .withAssigneeUserId("u-asg");
        JsonNode n = json.readTree(json.writeValueAsString(dto));
        assertThat(n.get("requesterUserId").asText()).isEqualTo("u-req");
        assertThat(n.get("assigneeUserId").asText()).isEqualTo("u-asg");
        assertThat(n.get("requesterEmail").asText()).isEqualTo("req@x.com");
        // The ids survive every later fold.
        PostDto later = dto.withEngagement(1, true, 2).withAssignees(List.of());
        assertThat(later.requesterUserId()).isEqualTo("u-req");
        assertThat(later.assigneeUserId()).isEqualTo("u-asg");
    }

    @Test
    void anUnresolvedPersonShipsNoIdKey() throws Exception {
        JsonNode n = json.readTree(json.writeValueAsString(PostDto.fromEntity(task())));
        assertThat(n.has("requesterUserId")).isFalse();
        assertThat(n.has("assigneeUserId")).isFalse();
    }

    @Test
    void assigneeRowCarriesUserId() throws Exception {
        var a = new PostDto.AssigneeDto("a@x.com", "A", null, "LEAD", true, "u-a");
        JsonNode n = json.readTree(json.writeValueAsString(a));
        assertThat(n.get("userId").asText()).isEqualTo("u-a");
        assertThat(n.get("email").asText()).isEqualTo("a@x.com");
    }

    @Test
    void reactionRosterCarriesUserId_resolvedInOneBatchAcrossPosts() throws Exception {
        GroupPostReactionRepo repo = mock(GroupPostReactionRepo.class);
        UserInfoRepo users = mock(UserInfoRepo.class);
        GroupPostReactionService svc = new GroupPostReactionService(repo, mock(GroupPostRepo.class), mock(WebSocketMessageSender.class));
        ReflectionTestUtils.setField(svc, "userInfoRepo", users);

        List<GroupPostReaction> rows = new ArrayList<>();
        for (long post = 1; post <= 3; post++) {
            GroupPostReaction r = new GroupPostReaction();
            r.setPostId(post);
            r.setUserEmail(post == 3 ? "Gone@x.com" : "Ann@x.com");
            r.setEmoji("x");
            r.setAddedAt(Instant.parse("2026-10-13T10:00:00Z"));
            rows.add(r);
        }
        when(repo.findByPostIdIn(anyList())).thenReturn(rows);
        when(users.findByUserEmailIn(anyList())).thenReturn(List.of(user("u-ann", "ann@x.com")));

        var out = svc.loadByPostIds(List.of(1L, 2L, 3L));

        verify(users, times(1)).findByUserEmailIn(anyList()); // no N+1
        EmojiReactionDto ann = out.get(1L).get("x").get(0);
        assertThat(ann.userEmail()).isEqualTo("Ann@x.com");
        assertThat(ann.userId()).isEqualTo("u-ann");
        JsonNode wire = json.readTree(json.writeValueAsString(Map.of("x", out.get(1L).get("x")))).get("x").get(0);
        assertThat(wire.get("userId").asText()).isEqualTo("u-ann");
        assertThat(wire.get("userEmail").asText()).isEqualTo("Ann@x.com");
        // A reactor with no profile row ships no userId key rather than a null.
        assertThat(json.writeValueAsString(out.get(3L).get("x").get(0))).doesNotContain("userId");
    }
}
