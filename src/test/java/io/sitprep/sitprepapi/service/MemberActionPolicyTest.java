package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one answer to "may this viewer act on other members here" — the map's
 * viewerCapabilities and the nudge / check-in endpoints both read it.
 */
class MemberActionPolicyTest {

    private static Group group(String type) {
        Group g = new Group();
        g.setGroupId("g-1");
        g.setGroupType(type);
        g.setOwnerEmail("owner@x.com");
        g.setAdminEmails(new ArrayList<>(List.of("admin@x.com")));
        g.setMemberEmails(new ArrayList<>(List.of("owner@x.com", "admin@x.com", "member@x.com")));
        return g;
    }

    @Test
    void householdMembersCanPingAndAskEveryoneButNotAnswerForOthers() {
        MemberActionPolicy.Capabilities c = MemberActionPolicy.of(group("Household"), "member@x.com");
        assertThat(c.nudge()).isTrue();
        assertThat(c.askEveryone()).isTrue();
        assertThat(c.setOthersStatus()).isFalse();
        assertThat(c.pingMissing()).isFalse();
    }

    @Test
    void groupMembersGetNoMemberActions_ownerRulingD2() {
        MemberActionPolicy.Capabilities c = MemberActionPolicy.of(group("HOA/Neighborhood"), "MEMBER@x.com ");
        assertThat(c).isEqualTo(new MemberActionPolicy.Capabilities(false, false, false, false));
    }

    @Test
    void adminsAndOwnersGetEverythingInAnyGroup() {
        for (String type : List.of("Household", "HOA/Neighborhood")) {
            for (String who : List.of("admin@x.com", "owner@x.com")) {
                assertThat(MemberActionPolicy.of(group(type), who))
                        .as("%s in %s", who, type)
                        .isEqualTo(new MemberActionPolicy.Capabilities(true, true, true, true));
            }
        }
    }

    @Test
    void outsidersAndAnonymousGetNothing() {
        for (String who : new String[]{"stranger@x.com", "", null}) {
            assertThat(MemberActionPolicy.of(group("Household"), who))
                    .isEqualTo(new MemberActionPolicy.Capabilities(false, false, false, false));
        }
    }

    @Test
    void anAdminOffTheMemberRosterCanAnswerForOthersButNotNudge() {
        // The status and ping-missing endpoints check role only; nudge and
        // ask-everyone also require the roster, as they always have.
        Group g = group("HOA/Neighborhood");
        g.setMemberEmails(new ArrayList<>(List.of("member@x.com")));
        MemberActionPolicy.Capabilities c = MemberActionPolicy.of(g, "admin@x.com");
        assertThat(c.setOthersStatus()).isTrue();
        assertThat(c.pingMissing()).isTrue();
        assertThat(c.nudge()).isFalse();
        assertThat(c.askEveryone()).isFalse();
    }
}
