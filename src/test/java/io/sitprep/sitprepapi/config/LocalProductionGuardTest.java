package io.sitprep.sitprepapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.mock.env.MockEnvironment;

import static io.sitprep.sitprepapi.config.LocalProductionGuard.BREAK_GLASS_ENV;
import static io.sitprep.sitprepapi.config.LocalProductionGuard.BREAK_GLASS_VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The P1 local→production guard: development runtimes fail closed on a non-local database. */
class LocalProductionGuardTest {

    private static final String PROD_LIKE = "jdbc:postgresql://cluster-abc.us-east-1.rds.amazonaws.com:5432/db?sslmode=require";
    private final LocalProductionGuard guard = new LocalProductionGuard();

    private static MockEnvironment env(String url) {
        MockEnvironment e = new MockEnvironment();
        if (url != null) e.setProperty("spring.datasource.url", url);
        return e;
    }

    private void run(MockEnvironment e) {
        guard.postProcessEnvironment(e, new SpringApplication());
    }

    @Test
    void herokuProductionIsUntouched() {
        MockEnvironment e = env(PROD_LIKE);
        e.setProperty("DYNO", "web.1");
        assertThatCode(() -> run(e)).doesNotThrowAnyException();
        assertThat(e.getProperty("r2.account-id")).isNull();
        assertThat(e.getProperty("app.scheduling.enabled")).isNull();
    }

    @Test
    void aLaptopWithNoProfileRefusesTheProductionDatabase() {
        // The real hazard: IntelliJ, no profile, application-local.yml pointing at RDS.
        assertThatThrownBy(() -> run(env(PROD_LIKE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-local database")
                .hasMessageContaining(BREAK_GLASS_ENV)
                .hasMessageNotContaining("rds.amazonaws.com");
    }

    @Test
    void aDevelopmentProfileRefusesEvenOnADyno() {
        MockEnvironment e = env(PROD_LIKE);
        e.setProperty("DYNO", "run.1");
        e.setActiveProfiles("practice-preview");
        assertThatThrownBy(() -> run(e)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyTheExactBreakGlassValueUnlocks() {
        MockEnvironment wrong = env(PROD_LIKE);
        wrong.setProperty(BREAK_GLASS_ENV, "true");
        assertThatThrownBy(() -> run(wrong)).isInstanceOf(IllegalStateException.class);

        MockEnvironment right = env(PROD_LIKE);
        right.setProperty(BREAK_GLASS_ENV, BREAK_GLASS_VALUE);
        assertThatCode(() -> run(right)).doesNotThrowAnyException();
        assertThat(right.getProperty("r2.account-id")).isNull(); // break glass leaves storage as configured
        assertThat(right.getProperty("app.scheduling.enabled")).isEqualTo("false"); // but never a second scheduler
    }

    @Test
    void aLocalDatabaseStartsWithJobsOffAndStorageDisabled() {
        MockEnvironment e = env("jdbc:postgresql://localhost:5432/sitprep_integration");
        e.setProperty("r2.account-id", "real-account");
        run(e);
        assertThat(e.getProperty("app.scheduling.enabled")).isEqualTo("false");
        assertThat(e.getProperty("r2.account-id")).isEqualTo(LocalProductionGuard.PLACEHOLDER);
        assertThat(e.getProperty("r2.access-key-id")).isEqualTo(LocalProductionGuard.PLACEHOLDER);
        assertThat(e.getProperty("r2.secret-access-key")).isEqualTo(LocalProductionGuard.PLACEHOLDER);
    }

    @Test
    void anExplicitSchedulingSettingStillWinsLocally() {
        MockEnvironment e = env("jdbc:h2:mem:test");
        e.setProperty("app.scheduling.enabled", "true");
        run(e);
        assertThat(e.getProperty("app.scheduling.enabled")).isEqualTo("true");
    }

    @Test
    void localDatabaseRecognition() {
        assertThat(LocalProductionGuard.isLocalDatabase(null)).isTrue();
        assertThat(LocalProductionGuard.isLocalDatabase("jdbc:h2:mem:x;MODE=PostgreSQL")).isTrue();
        assertThat(LocalProductionGuard.isLocalDatabase("jdbc:postgresql://localhost/x")).isTrue();
        assertThat(LocalProductionGuard.isLocalDatabase("jdbc:postgresql://127.0.0.1:5432/x?user=a")).isTrue();
        assertThat(LocalProductionGuard.isLocalDatabase("jdbc:postgresql://[::1]:5432/x")).isTrue();
        assertThat(LocalProductionGuard.isLocalDatabase("jdbc:postgresql://user:pw@localhost:5432/x")).isTrue();
        assertThat(LocalProductionGuard.isLocalDatabase(PROD_LIKE)).isFalse();
        assertThat(LocalProductionGuard.isLocalDatabase("jdbc:postgresql://localhost.evil.example/x")).isFalse();
        assertThat(LocalProductionGuard.isLocalDatabase("jdbc:postgresql://localhost,db.example.com/x")).isFalse();
        assertThat(LocalProductionGuard.isLocalDatabase("jdbc:postgresql://u:p@db.example.com/x")).isFalse();
    }

    @Test
    @SuppressWarnings("deprecation")
    void isRegisteredSoSpringBootRunsIt() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader()))
                .contains(LocalProductionGuard.class.getName());
    }
}
