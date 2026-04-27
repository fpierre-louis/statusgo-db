package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.StorageService;
import io.sitprep.sitprepapi.service.StorageService.Scope;
import io.sitprep.sitprepapi.service.StorageService.UploadResult;
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
 * Phase E auth-gate TODO: once Spring Security is wired to verify Firebase
 * ID tokens, derive the uploader from the principal and reject if absent.
 */
@RestController
@RequestMapping("/api/images")
public class ImageResource {

    private final StorageService storage;

    public ImageResource(StorageService storage) {
        this.storage = storage;
    }

    @PostMapping
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("scope") Scope scope
    ) throws IOException {
        UploadResult r = storage.upload(file, scope);
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
        storage.delete(keyOrUrl);
        return ResponseEntity.noContent().build();
    }
}
