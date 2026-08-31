package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an Ask payload may contain, asserted on the serialised JSON.
 *
 * <p>Ask reads are anonymous by product decision — {@code /api/ask/questions},
 * {@code /api/ask/questions/*}, {@code /api/ask/tips}, {@code /api/ask/tips/*}
 * and {@code /api/ask/search} are all {@code permitAll} in SecurityConfig. Until
 * 2026-08-31 these DTOs shipped {@code authorEmail} and, for questions and tips,
 * a 7-decimal coordinate pair. Measured against prod v553 with no token:
 *
 * <pre>
 * GET /api/ask/questions  →  200
 * "authorEmail":"&lt;a real personal address&gt;"
 * "latitude":40.4376647,"longitude":-111.8804517
 * </pre>
 *
 * <p><b>These assertions are on the JSON, not on the field list</b>, and that is
 * the point. Two of the three fixes here are annotations rather than deletions —
 * {@code @JsonIgnore} on the search hit's join key, {@code Access.WRITE_ONLY} on
 * the coordinates — so a field-presence check would pass while the leak was
 * wide open. Only serialising catches it.</p>
 */
class AskDtoPrivacyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String json(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    @Test
    @DisplayName("a question never serialises an email or a coordinate")
    void questionIsClean() throws Exception {
        AskQuestionDto d = new AskQuestionDto();
        d.setAuthorUserId("u-123");
        d.setLatitude(40.4376647);
        d.setLongitude(-111.8804517);

        String out = json(d);
        assertThat(out).doesNotContain("authorEmail");
        assertThat(out).doesNotContain("40.4376647");
        assertThat(out).doesNotContain("-111.8804517");
        assertThat(out).contains("\"authorUserId\":\"u-123\"");
    }

    @Test
    @DisplayName("but a question still ACCEPTS coordinates — compose depends on it")
    void questionStillBindsCoordinates() throws Exception {
        // WRITE_ONLY, not removed: this DTO is the request body too, and
        // createQuestion reads in.getLatitude(). Deleting the field would have
        // silently dropped the author's location on every new post — trading a
        // privacy leak for a data-loss bug.
        AskQuestionDto in = mapper.readValue(
                "{\"title\":\"t\",\"body\":\"b\",\"latitude\":40.5,\"longitude\":-111.9}",
                AskQuestionDto.class);
        assertThat(in.getLatitude()).isEqualTo(40.5);
        assertThat(in.getLongitude()).isEqualTo(-111.9);
    }

    @Test
    @DisplayName("a tip never serialises an email or a coordinate, and still accepts them")
    void tipIsClean() throws Exception {
        AskTipDto d = new AskTipDto();
        d.setAuthorUserId("u-9");
        d.setLatitude(40.4376647);
        d.setLongitude(-111.8804517);

        String out = json(d);
        assertThat(out).doesNotContain("authorEmail");
        assertThat(out).doesNotContain("40.4376647");
        assertThat(out).contains("\"authorUserId\":\"u-9\"");

        AskTipDto in = mapper.readValue("{\"latitude\":1.5,\"longitude\":2.5}", AskTipDto.class);
        assertThat(in.getLatitude()).isEqualTo(1.5);
    }

    @Test
    @DisplayName("an answer never serialises an email")
    void answerIsClean() throws Exception {
        AskAnswerDto d = new AskAnswerDto();
        d.setAuthorUserId("u-7");
        assertThat(json(d)).doesNotContain("authorEmail");
        assertThat(json(d)).contains("\"authorUserId\":\"u-7\"");
    }

    @Test
    @DisplayName("a search hit keeps the email as an INTERNAL join key and never emits it")
    void searchHitJoinKeyStaysInternal() throws Exception {
        // The one that cannot simply be deleted: foldSearchAuthors batch-loads
        // each author's UserInfo by email, so the DTO carries it between two
        // server-side steps. @JsonIgnore is the whole guarantee, and removing
        // that annotation reopens the leak with no other symptom. This fails.
        AskSearchHitDto h = new AskSearchHitDto();
        h.setAuthorEmail("someone@example.com");
        h.setAuthorUserId("u-42");

        String out = json(h);
        assertThat(h.getAuthorEmail()).isEqualTo("someone@example.com"); // still usable in-process
        assertThat(out).doesNotContain("authorEmail");
        assertThat(out).doesNotContain("someone@example.com");
        assertThat(out).contains("\"authorUserId\":\"u-42\"");
    }
}
