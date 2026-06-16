package io.sitprep.sitprepapi.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DtoImagesTest {

    @Test
    void passesThroughAbsoluteProviderUrls() {
        assertThat(DtoImages.avatar(" https://lh3.googleusercontent.com/a/photo.jpg "))
                .isEqualTo("https://lh3.googleusercontent.com/a/photo.jpg");
    }

    @Test
    void normalizesProtocolRelativeUrlsToHttps() {
        assertThat(DtoImages.avatar("//example.com/avatar.png"))
                .isEqualTo("https://example.com/avatar.png");
    }

    @Test
    void normalizesBareObjectKeysToPublicCdnUrls() {
        assertThat(DtoImages.avatar("avatars/user-1.png"))
                .isEqualTo("https://sitprepimages.com/avatars/user-1.png");
    }

    @Test
    void dropsOpaqueOrMalformedValues() {
        assertThat(DtoImages.avatar(null)).isNull();
        assertThat(DtoImages.avatar("")).isNull();
        assertThat(DtoImages.avatar("data:image/png;base64,abc")).isNull();
        assertThat(DtoImages.avatar("blob:https://example.com/123")).isNull();
        assertThat(DtoImages.avatar("../secret.png")).isNull();
        assertThat(DtoImages.avatar("https://")).isNull();
    }
}
