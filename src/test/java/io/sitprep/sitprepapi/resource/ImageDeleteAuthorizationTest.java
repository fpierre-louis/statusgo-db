package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.StorageService;
import io.sitprep.sitprepapi.service.StorageService.ObjectOwner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DELETE /api/images may only remove an object the caller uploaded.
 *
 * <p>Before 2026-08-24 this endpoint checked only that a caller was signed in,
 * and the comment in the code said as much. Image keys ship in readable feed
 * payloads, so any account could scrape keys and permanently destroy other
 * users' avatars, group logos and work-order evidence photos.</p>
 *
 * <p>The stranger case below is the regression this file exists to hold. The
 * two edge cases are equally deliberate and equally easy to "fix" wrongly later:
 * a missing object answers 204 so the frontend's cleanup paths stay idempotent,
 * and an object with no uploader stamp (everything uploaded before this change)
 * is refused, because "no recorded owner" cannot be read as "yours".</p>
 *
 * <p>No Spring context — the resource is invoked directly with a stubbed
 * SecurityContext, matching PostAssignAuthorizationTest.</p>
 */
class ImageDeleteAuthorizationTest {

    private static final String OWNER = "owner@x.com";
    private static final String STRANGER = "stranger@x.com";
    private static final String KEY = "profile/abc-123.jpg";

    private StorageService storage;
    private ImageResource resource;

    @BeforeEach
    void setUp() {
        storage = mock(StorageService.class);
        resource = new ImageResource(storage);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    /** Stamp the object as uploaded by {@code email}, the way upload() does. */
    private void objectUploadedBy(String email) {
        when(storage.ownerOf(KEY)).thenReturn(new ObjectOwner(true, StorageService.uploaderTag(email)));
    }

    @Test
    void uploaderCanDeleteTheirOwnImage() {
        authenticateAs(OWNER);
        objectUploadedBy(OWNER);

        assertEquals(HttpStatus.NO_CONTENT, resource.delete(KEY).getStatusCode());
        verify(storage).delete(KEY);
    }

    @Test
    void strangerCannotDeleteSomeoneElsesImage() {
        authenticateAs(STRANGER);
        objectUploadedBy(OWNER);

        ResponseStatusException e =
                assertThrows(ResponseStatusException.class, () -> resource.delete(KEY));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(storage, never()).delete(any());
    }

    @Test
    void emailCaseDoesNotDecideOwnership() {
        authenticateAs("Owner@X.com");
        objectUploadedBy(OWNER);

        assertEquals(HttpStatus.NO_CONTENT, resource.delete(KEY).getStatusCode());
        verify(storage).delete(KEY);
    }

    @Test
    void unstampedLegacyObjectIsRefusedRatherThanAssumedYours() {
        authenticateAs(OWNER);
        when(storage.ownerOf(KEY)).thenReturn(new ObjectOwner(true, null));

        ResponseStatusException e =
                assertThrows(ResponseStatusException.class, () -> resource.delete(KEY));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(storage, never()).delete(any());
    }

    @Test
    void deletingAnAlreadyGoneObjectIsANoOpNotAnError() {
        // Both callers are cleanup paths (R2ImageUploader dropping a staged
        // upload, EditProfilePage clearing a replaced avatar) and may fire twice.
        authenticateAs(OWNER);
        when(storage.ownerOf(KEY)).thenReturn(new ObjectOwner(false, null));

        assertEquals(HttpStatus.NO_CONTENT, resource.delete(KEY).getStatusCode());
        verify(storage, never()).delete(any());
    }

    @Test
    void uploaderTagIsStableAndCaseInsensitive() {
        assertEquals(StorageService.uploaderTag(OWNER), StorageService.uploaderTag("  OWNER@X.COM  "));
        assertNotEquals(StorageService.uploaderTag(OWNER), StorageService.uploaderTag(STRANGER));
        assertNull(StorageService.uploaderTag(null));
        assertNull(StorageService.uploaderTag("  "));
    }
}
