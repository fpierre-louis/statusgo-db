package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.StorageService;
import io.sitprep.sitprepapi.service.StorageService.Scope;
import io.sitprep.sitprepapi.service.StorageService.UploadResult;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

/**
 * Single upload pipe for every image surface in SitPrep — profile, post,
 * task attachment, group cover. Caller PATCHes the returned {@code imageId}
 * (or {@code imageKey}) onto the appropriate entity afterwards.
 *
 * <p>Phase E enforcement live: both endpoints call
 * {@link AuthUtils#requireAuthenticatedEmail()} which throws 401 when no
 * verified Firebase token is on the request. Frontend attaches the token
 * via the http.js axios interceptor. The R2 bucket is the most expensive
 * resource on the API so this was the first endpoint to flip — anonymous
 * spam writes here cost real money.</p>
 */
@RestController
@RequestMapping("/api/images")
public class ImageResource {

    private static final Logger log = LoggerFactory.getLogger(ImageResource.class);

    private final StorageService storage;

    public ImageResource(StorageService storage) {
        this.storage = storage;
    }

    @PostMapping
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("scope") Scope scope
    ) throws IOException {
        String uploader = AuthUtils.requireAuthenticatedEmail(); // 401 if no verified token
        UploadResult r = storage.upload(file, scope, uploader);
        log.info("Image uploaded by {} scope={} key={}", uploader, scope, r.imageKey());
        return ResponseEntity
                .status(201)
                .body(Map.of(
                        "imageId", r.imageId(),
                        "imageKey", r.imageKey(),
                        "imageUrl", r.imageUrl()
                ));
    }

    /**
     * Delete an image you uploaded.
     *
     * <p>Until 2026-08-24 this required only a signed-in caller, and the comment
     * here said so: "any signed-in user can delete any image". Image keys are not
     * secret — they ship in every PostDto.imageKey, Group.logoImageUrl,
     * UserInfo.profileImageUrl and HouseholdManualMemberDto.photoUrl — so
     * scraping keys from a readable feed and destroying every avatar, group logo
     * and work-order evidence photo in the bucket was a loop, irreversibly.</p>
     *
     * <p>Ownership now comes from the uploader stamp written at upload time. Two
     * outcomes are deliberate:</p>
     * <ul>
     *   <li><b>Object already gone → 204, not 403.</b> Both callers
     *       (R2ImageUploader removing a staged upload, EditProfilePage clearing
     *       a replaced avatar) are cleanup paths that must stay idempotent.</li>
     *   <li><b>Object predates stamping → 403.</b> Every image uploaded before
     *       this change carries no uploader, and the honest reading of "no
     *       recorded owner" is "cannot prove you own it". The cost is an orphaned
     *       object in R2; the alternative is leaving the hole open for the whole
     *       existing bucket. Both call sites already swallow the failure, so this
     *       degrades to wasted storage, never a broken flow.</li>
     * </ul>
     *
     * <p>There is deliberately no platform-admin bypass. Nothing needs one today,
     * and server-initiated cascades (a post's photos going with the post) call
     * StorageService.delete directly and are unaffected by this gate.</p>
     */
    @DeleteMapping
    public ResponseEntity<Void> delete(@RequestParam("keyOrUrl") String keyOrUrl) {
        String requester = AuthUtils.requireAuthenticatedEmail(); // 401 if no verified token

        StorageService.ObjectOwner owner = storage.ownerOf(keyOrUrl);
        if (!owner.exists()) {
            return ResponseEntity.noContent().build(); // nothing to delete
        }
        String callerTag = StorageService.uploaderTag(requester);
        if (owner.uploaderTag() == null || !owner.uploaderTag().equals(callerTag)) {
            log.warn("Image delete REFUSED for {} keyOrUrl={} (stamped={})",
                    requester, keyOrUrl, owner.uploaderTag() != null);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only delete images you uploaded");
        }

        storage.delete(keyOrUrl);
        log.info("Image deleted by {} keyOrUrl={}", requester, keyOrUrl);
        return ResponseEntity.noContent().build();
    }
}
