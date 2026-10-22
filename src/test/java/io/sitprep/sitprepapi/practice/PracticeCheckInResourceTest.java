package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.web.Idempotent;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** The contract's paths and idempotency, exactly as FR-7 states them. */
class PracticeCheckInResourceTest {

    private static Method method(String name) {
        return Arrays.stream(PracticeCheckInResource.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void readsAreGetsAndWritesAreIdempotentPosts() {
        assertThat(method("due").getAnnotation(GetMapping.class).value()).containsExactly("/api/practice/checkins/due");
        assertThat(method("skillState").getAnnotation(GetMapping.class).value()).containsExactly("/api/practice/skills/state");
        assertThat(method("answer").getAnnotation(PostMapping.class).value())
                .containsExactly("/api/practice/checkins/{checkinKey}/answer");
        assertThat(method("confidence").getAnnotation(PostMapping.class).value())
                .containsExactly("/api/practice/checkins/answers/{answerId}/confidence");
        assertThat(method("answer").isAnnotationPresent(Idempotent.class)).isTrue();
        assertThat(method("confidence").isAnnotationPresent(Idempotent.class)).isTrue();
        assertThat(method("due").isAnnotationPresent(Idempotent.class)).isFalse();
    }

    @Test
    void theWireNamesAreTheContractsNames() {
        assertThat(Arrays.stream(PracticeCheckInDtos.DueDto.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("schemaVersion", "hazard", "hazardLabel", "cards", "suppression");
        assertThat(Arrays.stream(PracticeCheckInDtos.CardDto.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("checkinKey", "version", "skillKey", "prompt", "choices", "objective");
        assertThat(Arrays.stream(PracticeCheckInDtos.AnswerDto.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("answerId", "remember", "explanation");
        assertThat(Arrays.stream(PracticeCheckInDtos.SkillStateDto.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("skillKey", "status", "lastPracticedAt");
        assertThat(Arrays.stream(ScenarioDtos.RefreshDto.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("hazard", "hazardLabel", "title", "count");
        assertThat(Arrays.stream(PracticeCheckInDtos.AnswerRequest.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("householdId", "choiceKey");
    }
}
