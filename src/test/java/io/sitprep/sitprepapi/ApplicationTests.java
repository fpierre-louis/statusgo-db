package io.sitprep.sitprepapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Context-loads smoke test. {@code @ActiveProfiles("test")} routes the JPA
 * datasource at {@code src/test/resources/application-test.yml} — an in-memory
 * H2 instance — so the test never touches prod RDS.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationTests {

	@Test
	void contextLoads() {
	}

}
