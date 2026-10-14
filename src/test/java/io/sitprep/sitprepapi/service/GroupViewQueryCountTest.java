package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-10 (B4, 2026-10-14): the member view costs a FIXED number of SQL
 * statements however many members the group has. Before, it ran one
 * notification-log query per member, one name lookup per proxy-set status,
 * and read every post the group ever made.
 *
 * <p>Real services over the H2 schema; every SQL statement is counted by a
 * Hibernate {@code StatementInspector}. The same group shape (owner viewer —
 * so the pending list and a linked sub group are built too — a running
 * check-in, every member's status set by someone off the roster) at 5
 * members and at 50.</p>
 *
 * <p><b>One cost is not this service's.</b> {@code UserInfo} has three EAGER
 * element collections ({@code user_managed_group_ids},
 * {@code user_joined_group_ids}, {@code user_info_group_location_sharing}),
 * which Hibernate loads in batches of {@code default_batch_fetch_size: 10}
 * (application.yml, a deliberate 2026-06-07 memory trim). Those grow as
 * ceil(accounts / 10) per collection and are asserted separately; everything
 * else must not grow at all.</p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "io.sitprep.sitprepapi.service.GroupViewQueryCountTest$Counter")
@ActiveProfiles("test")
class GroupViewQueryCountTest {

    @MockBean WebSocketMessageSender ws;
    @MockBean NotificationService notifications;

    @Autowired GroupViewService groupView;
    @Autowired GroupRepo groups;
    @Autowired UserInfoRepo users;

    /** Collects every SQL statement Hibernate prepares. */
    public static class Counter implements StatementInspector {
        static final List<String> SQL = java.util.Collections.synchronizedList(new ArrayList<>());
        @Override public String inspect(String sql) { SQL.add(sql); return sql; }
    }

    private static final List<String> USER_COLLECTIONS = List.of(
            "user_managed_group_ids", "user_joined_group_ids", "user_info_group_location_sharing");

    private static boolean isUserCollection(String sql) {
        String s = sql.toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("select") && USER_COLLECTIONS.stream().anyMatch(t -> s.contains(" from " + t));
    }

    @Test
    void fiftyMembersCostTheSameStatementsAsFive() {
        Run five = statementsFor(5);
        Run fifty = statementsFor(50);
        assertThat(fifty.own())
                .as("this service's statements for 50 members (5 members took %d): %s", five.own(), fifty.sql())
                .isEqualTo(five.own());
        // The entity's eager collections: batched, never one per account.
        int accounts = 50 + 49 + 10; // members + their proxies + pending
        assertThat(fifty.userCollections()).isLessThanOrEqualTo(3 * (int) Math.ceil(accounts / 10.0));
    }

    record Run(long own, long userCollections, List<String> sql) {}

    private Run statementsFor(int memberCount) {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        String owner = "owner-" + sfx + "@x.com";
        user(owner, null);
        List<String> members = new ArrayList<>(List.of(owner));
        for (int i = 0; i < memberCount - 1; i++) {
            String e = "m" + i + "-" + sfx + "@x.com";
            // Every status was set FOR them, by people who aren't on the
            // roster: the worst case for the old per-member name lookup.
            user(e, "proxy" + i + "-" + sfx + "@x.com");
            user("proxy" + i + "-" + sfx + "@x.com", null);
            members.add(e);
        }
        List<String> pending = new ArrayList<>();
        for (int i = 0; i < Math.max(1, memberCount / 5); i++) {
            String e = "p" + i + "-" + sfx + "@x.com";
            user(e, null);
            pending.add(e);
        }

        Group child = group("child-" + sfx, owner, List.of(owner), List.of(), List.of());
        Group parent = group("parent-" + sfx, owner, members, pending, List.of(child.getGroupId()));

        Counter.SQL.clear();
        GroupMemberViewDto view = groupView.buildMemberView(parent.getGroupId(), owner).orElseThrow();
        List<String> sql = new ArrayList<>(Counter.SQL);
        long collections = sql.stream().filter(GroupViewQueryCountTest::isUserCollection).count();

        // The view really built what was counted.
        assertThat(view.members()).hasSize(memberCount);
        assertThat(view.pendingMembers()).hasSize(pending.size());
        assertThat(view.subGroups()).hasSize(1);
        assertThat(view.members().get(1).selfStatus().setByName()).startsWith("Proxy");
        return new Run(sql.size() - collections, collections, sql);
    }

    private Group group(String id, String owner, List<String> members, List<String> pending, List<String> subs) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupType("Community");
        g.setGroupName("Count " + id);
        g.setPrivacy("Private");
        g.setAlert("Active");
        g.setAlertActivatedAt(Instant.now().minusSeconds(3600));
        g.setOwnerEmail(owner);
        g.setAdminEmails(new ArrayList<>(List.of(owner)));
        g.setMemberEmails(new ArrayList<>(members));
        g.setPendingMemberEmails(new ArrayList<>(pending));
        g.setSubGroupIDs(new ArrayList<>(subs));
        g.setCreatedAt(Instant.now());
        g.setUpdatedAt(Instant.now());
        return groups.save(g);
    }

    private void user(String email, String setBy) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setUserFirstName(email.startsWith("proxy") ? "Proxy" : "Name");
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setUserStatus("SAFE");
        u.setUserStatusLastUpdated(Instant.now());
        u.setStatusSetByEmail(setBy);
        users.save(u);
    }
}
