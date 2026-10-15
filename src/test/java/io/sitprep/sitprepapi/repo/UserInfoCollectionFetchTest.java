package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.UserInfo;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A roster read loads many UserInfo rows; their three eager element
 * collections must not cost a statement per batch of 10 accounts each
 * (build-10 backlog: 3 x ceil(accounts/10) per roster read). With
 * {@code @Fetch(SUBSELECT)} each collection is one statement for the whole
 * result, whatever the roster size.
 */
@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class UserInfoCollectionFetchTest {

    @Autowired private UserInfoRepo repo;
    @Autowired private TestEntityManager em;
    @Autowired private EntityManagerFactory emf;

    @Test
    void aLargeRosterReadLoadsEachCollectionInOneStatement() {
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            UserInfo u = new UserInfo();
            String email = "member" + i + "@example.com";
            u.setUserEmail(email);
            u.setJoinedGroupIDs(new HashSet<>(Set.of("g-1", "g-own-" + i)));
            u.setManagedGroupIDs(new HashSet<>(i % 5 == 0 ? Set.of("g-1") : Set.of()));
            u.setGroupLocationSharing(new HashMap<>(i % 2 == 0 ? java.util.Map.of("g-1", "always") : java.util.Map.of()));
            em.persist(u);
            emails.add(email);
        }
        em.flush();
        em.clear();

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        List<UserInfo> roster = repo.findByUserEmailIn(emails);
        int touched = 0;
        for (UserInfo u : roster) {
            touched += u.getJoinedGroupIDs().size() + u.getManagedGroupIDs().size()
                    + u.getGroupLocationSharing().size();
        }

        assertThat(roster).hasSize(35);
        assertThat(touched).isEqualTo(70 + 7 + 18); // every row's values arrived
        // 1 for the users + 1 per collection, not 3 x ceil(35/10) = 12 more.
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(4);
    }
}
