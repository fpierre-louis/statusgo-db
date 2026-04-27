package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.util.ImageOptimizer;
import io.sitprep.sitprepapi.util.PublicCdn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;

/**
 * One pipe for every image surface in SitPrep — profile, post, task, group cover.
 *
 * <p>Source bucket is {@code sitprep-images} on Cloudflare R2; delivered via
 * the {@code https://sitprepimages.com} custom domain. See
 * {@link io.sitprep.sitprepapi.config.R2Config} for client wiring and
 * {@link PublicCdn} for URL/key translation.</p>
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    /**
     * Per-scope storage rules. Backend hard caps: even if the frontend
     * resizer is bypassed, we never write a larger image than these to R2.
     */
    public enum Scope {
        profile("profile/", 512),
        post("post/", 1600),
        task("task/", 2048),
        group_cover("group/", 1600);

        public final String prefix;
        public final int maxLongEdge;

        Scope(String prefix, int maxLongEdge) {
            this.prefix = prefix;
            this.maxLongEdge = maxLongEdge;
        }
    }

    public record UploadResult(String imageId, String imageKey, String imageUrl) {}

    private final S3Client s3;

    @Value("${r2.bucket-name:sitprep-images}")
    private String bucketName;

    @Value("${r2.media.max-pixels:30000000}")
    private long maxPixels;

    @Value("${r2.media.jpeg-quality:0.82}")
    private float jpegQuality;

    public StorageService(S3Client s3) {
        this.s3 = s3;
    }

    /**
     * Resize, compress, and upload. Returns the imageId (UUID), the object
     * key to store on the entity, and the public URL for delivery.
     */
    public UploadResult upload(MultipartFile file, Scope scope) throws IOException {
        if (file == null || file.isEmpty()) throw new IOException("Empty file");
        if (scope == null) throw new IllegalArgumentException("scope required");

        BufferedImage original;
        try (var is = file.getInputStream()) {
            original = ImageIO.read(is);
        }
        if (original == null) throw new IOException("Unsupported image format");

        ImageOptimizer.OptimizedImage optimized = ImageOptimizer.optimize(
                original, scope.maxLongEdge, jpegQuality, maxPixels);

        String imageId = UUID.randomUUID().toString();
        String key = scope.prefix + imageId + "." + optimized.extension();

        putObject(key, optimized.bytes(), optimized.contentType());
        return new UploadResult(imageId, key, PublicCdn.toPublicUrl(key));
    }

    /** Accepts either a key ({@code post/abc.jpg}) or a full URL; deletes the underlying object. */
    public void delete(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return;
        deleteImages(List.of(keyOrUrl));
    }

    public void deleteImages(List<String> keysOrUrls) {
        if (keysOrUrls == null || keysOrUrls.isEmpty()) return;

        List<String> cleaned = keysOrUrls.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(PublicCdn::toObjectKey)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        if (cleaned.isEmpty()) return;

        final int BATCH = 1000;
        for (int i = 0; i < cleaned.size(); i += BATCH) {
            List<String> batch = cleaned.subList(i, Math.min(i + BATCH, cleaned.size()));

            List<ObjectIdentifier> objects = new ArrayList<>(batch.size());
            for (String k : batch) objects.add(ObjectIdentifier.builder().key(k).build());

            DeleteObjectsRequest req = DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(objects).quiet(true).build())
                    .build();

            try {
                DeleteObjectsResponse resp = s3.deleteObjects(req);
                if (resp.hasErrors() && resp.errors() != null && !resp.errors().isEmpty()) {
                    for (S3Error err : resp.errors()) {
                        log.warn("R2 delete error key={} code={} msg={}", err.key(), err.code(), err.message());
                    }
                }
            } catch (Exception e) {
                log.error("R2 deleteObjects failed (batch {} keys): {}", batch.size(), e.getMessage());
                throw new RuntimeException("Failed to delete images from storage", e);
            }
        }
    }

    private void putObject(String key, byte[] content, String contentType) {
        String ct = (contentType == null || contentType.isBlank())
                ? "application/octet-stream" : contentType;
        try {
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(ct)
                    .cacheControl("public, max-age=31536000, immutable")
                    .build();
            s3.putObject(req, RequestBody.fromBytes(content));
        } catch (Exception e) {
            log.error("R2 putObject failed for key={}: {}", key, e.getMessage());
            throw new RuntimeException("Failed to upload file to storage", e);
        }
    }
}
