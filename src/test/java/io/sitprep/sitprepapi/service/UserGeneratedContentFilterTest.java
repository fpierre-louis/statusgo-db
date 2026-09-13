package io.sitprep.sitprepapi.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserGeneratedContentFilterTest {

    @Test
    void allowsOrdinaryEmergencyCoordination() {
        assertThatCode(() -> UserGeneratedContentFilter.requireAcceptable(
                "group post",
                "Shelter has bottled water at the west entrance.",
                "Bring cash if the card reader is still down."
        )).doesNotThrowAnyException();
    }

    @Test
    void blocksDirectSelfHarmHarassment() {
        assertThat(UserGeneratedContentFilter.looksObjectionable("you should kys"))
                .isTrue();
        assertThatThrownBy(() -> UserGeneratedContentFilter.requireAcceptable(
                "comment",
                "you should kill yourself"
        )).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void blocksEmergencyPaymentScamPattern() {
        assertThat(UserGeneratedContentFilter.looksObjectionable(
                "Emergency water delivery, venmo me first."
        )).isTrue();
    }
}
