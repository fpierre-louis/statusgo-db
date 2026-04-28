package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.StorageService;
import io.sitprep.sitprepapi.service.StorageService.Scope;
import io.sitprep.sitprepapi.service.StorageService.UploadResult;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
        UploadResult r = storage.upload(file, scope);
        log.info("Image uploaded by {} scope={} key={}", uploader, scope, r.imageKey());
        return ResponseEntity
                .status(201)
                .body(Map.of(
                        "imageId", r.imageId(),
                        "imageKey", r.imageKey(),
                        "imageUrl", r.imageUrl()
                ));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@RequestParam("keyOrUrl") String keyOrUrl) {
        String requester = AuthUtils.requireAuthenticatedEmail(); // 401 if no verified token
        // TODO: stronger ownership check — verify the imageKey was uploaded
        // by `requester`. Today any signed-in user can delete any image.
        // Tighten when we add an upload-owner column or query R2 metadata.
        storage.delete(keyOrUrl);
        log.info("Image deleted by {} keyOrUrl={}", requester, keyOrUrl);
        return ResponseEntity.noContent().build();
    }
}
