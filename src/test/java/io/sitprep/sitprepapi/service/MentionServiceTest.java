package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.MentionToken;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MentionDto;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Resolution rules: rename survival, tombstones, and the edit diff. */
class MentionServiceTest {

    private static final String ANA = "338ea7ea-4892-4073-b2c6-6f69a5167544";
    private static final String BEN = "7c20a1b0-dedb-46a8-8a1d-5b3e4ea6c484";
    private static final String GONE = "28c63505-fe99-478c-a068-500264364f3a";

    private UserInfoRepo repo;
    private MentionService service;

    private static UserInfo user(String id, String first, String last, String email) {
        UserInfo u = new UserInfo();
        u.setId(id);
        u.setUserFirstName(first);
        u.setUserLastName(last);
        u.setUserEmail(email);
        return u;
    }

    @BeforeEach
    void setUp() {
        repo = mock(UserInfoRepo.class);
        service = new MentionService(repo);
        when(repo.findAllById(any())).thenReturn(List.of(
                user(ANA, "Ana", "Reyes", "ana@x.com"),
                user(BEN, "Ben", "Ortiz", "ben@x.com")));
    }

    @Test
    @DisplayName("resolves to the CURRENT name -- a rename cannot unmake a mention")
    void resolvesCurrentName() {
        // The defect this design exists to prevent: under the plain-text scan
        // the reference lived in the name, so renaming broke the link silently.
        assertThat(service.resolve(List.of(ANA)))
                .containsExactly(new MentionDto(ANA, "Ana Reyes", false));
    }

    @Test
    @DisplayName("a deleted account resolves to a tombstone, in place, not dropped")
    void deletedAccountIsTombstoned() {
        // Order and arity must survive: the list lines up 1:1 with what the
        // content references, so the FE can render every token it finds.
        List<MentionDto> out = service.resolve(List.of(ANA, GONE));
        assertThat(out).hasSize(2);
        assertThat(out.get(1).userId()).isEqualTo(GONE);
        assertThat(out.get(1).deleted()).isTrue();
        assertThat(out.get(1).displayName()).isEqualTo(MentionToken.TOMBSTONE_NAME);
    }

    @Test
    @DisplayName("an account with no name set never falls back to its email")
    void namelessAccountDoesNotLeakEmail() {
        when(repo.findAllById(any())).thenReturn(List.of(user(ANA, null, null, "ana@x.com")));
        // A mention chip is a public surface; the email is not.
        assertThat(service.resolve(List.of(ANA)).get(0).displayName())
                .isEqualTo(MentionToken.TOMBSTONE_NAME);
    }

    @Test
    @DisplayName("edit notifies only what it ADDED")
    void editDiffAddsOnly() {
        String before = "hi " + MentionToken.of(ANA);
        String after = "hi " + MentionToken.of(ANA) + " and " + MentionToken.of(BEN);
        // Ana was already mentioned -- re-notifying her would make a typo fix
        // indistinguishable from being mentioned.
        assertThat(service.newlyMentioned(before, after)).containsExactly(BEN);
    }

    @Test
    @DisplayName("removing a mention notifies nobody")
    void removalNotifiesNobody() {
        String before = "hi " + MentionToken.of(ANA) + " " + MentionToken.of(BEN);
        String after = "hi " + MentionToken.of(ANA);
        assertThat(service.newlyMentioned(before, after)).isEmpty();
    }

    @Test
    @DisplayName("a first-time mention on an edit of previously-unmentioning text notifies")
    void editIntoEmptyPrevious() {
        assertThat(service.newlyMentioned("plain text", "now " + MentionToken.of(BEN)))
                .containsExactly(BEN);
    }

    @Test
    @DisplayName("push bodies resolve tokens rather than printing them")
    void plainTextForPushBodies() {
        assertThat(service.toPlainText("morning " + MentionToken.of(ANA)))
                .isEqualTo("morning @Ana Reyes");
    }
}
