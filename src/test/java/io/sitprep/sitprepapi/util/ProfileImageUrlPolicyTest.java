package io.sitprep.sitprepapi.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The avatar host allow-list (BE-1, 2026-09-27). Every URL here is rendered on
 * OTHER people's screens, so "any https URL" is a tracking pixel on every
 * roster that shows the user.
 */
class ProfileImageUrlPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // R2 public base — every StorageService upload lands here
            "https://sitprepimages.com/profile/abc.jpg",
            // DiceBear — the frontend avatar builder
            "https://api.dicebear.com/9.x/notionists/svg?seed=x",
            // Google sign-in photo
            "https://lh3.googleusercontent.com/a/ACg8ocK123=s96-c",
            // Facebook sign-in photo (web SDK, native SDK / redirect target, CDN)
            "https://graph.facebook.com/1234567890/picture",
            "https://platform-lookaside.fbsbx.com/platform/profilepic/?asid=1",
            "https://scontent-lax3-1.xx.fbcdn.net/v/t1.0-1/p100x100/1.jpg",
            // explicit default port is still https on the same host
            "https://sitprepimages.com:443/profile/abc.jpg",
            // host matching is case-insensitive
            "https://LH3.GoogleUserContent.com/a/x",
    })
    void allowsTheHostsTheAppActuallyWrites(String url) {
        assertThat(ProfileImageUrlPolicy.isAllowed(url)).as(url).isTrue();
        assertThat(ProfileImageUrlPolicy.normalizeForWrite("  " + url + "  ")).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://attacker.example/px.gif",
            // plain http — the host is fine, the scheme is not
            "http://lh3.googleusercontent.com/a/x",
            // suffix tricks
            "https://evilgoogleusercontent.com/a/x",
            "https://lh3.googleusercontent.com.attacker.example/a/x",
            "https://sitprepimages.com.attacker.example/x.jpg",
            // userinfo reads as one host and resolves to another
            "https://lh3.googleusercontent.com@attacker.example/x",
            // non-default port
            "https://sitprepimages.com:8443/x.jpg",
            // shared R2 dev domain = any Cloudflare customer's bucket
            "https://pub-0123456789abcdef.r2.dev/x.jpg",
            // opaque / script schemes
            "javascript:alert(1)",
            "data:image/png;base64,AAAA",
            "blob:https://sitprep.app/uuid",
            // a bare key or a relative path is not a URL a client may write
            "profile/abc.jpg",
            "/images/default-user-icon.png",
            // the bare suffix is not a host we write
            "https://googleusercontent.com/x",
            "not a url at all",
    })
    void refusesEverythingElse(String url) {
        assertThat(ProfileImageUrlPolicy.isAllowed(url)).as(url).isFalse();
        assertThatThrownBy(() -> ProfileImageUrlPolicy.normalizeForWrite(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankOrNullClears() {
        assertThat(ProfileImageUrlPolicy.normalizeForWrite(null)).isNull();
        assertThat(ProfileImageUrlPolicy.normalizeForWrite("")).isNull();
        assertThat(ProfileImageUrlPolicy.normalizeForWrite("   ")).isNull();
    }

    @Test
    void longerThanTheColumnIsRefused() {
        String longUrl = "https://lh3.googleusercontent.com/" + "a".repeat(300);
        assertThat(ProfileImageUrlPolicy.isAllowed(longUrl)).isFalse();
    }
}
