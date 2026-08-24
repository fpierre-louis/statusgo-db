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

    /**
     * What the bucket knows about an object's uploader.
     *
     * @param exists      false when the object is already gone — callers should
     *                    treat a delete of it as a no-op, not a rejection
     * @param uploaderTag {@link #uploaderTag(String)} of the uploader, or null
     *                    for objects written before uploader stamping existed
     */
    public record ObjectOwner(boolean exists, String uploaderTag) {}

    /**
     * R2/S3 user-metadata key carrying the uploader. Lowercase deliberately —
     * S3 lowercases metadata keys, so reading back any other casing returns null.
     */
    private static final String META_UPLOADER = "uploader";

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
     * Stable, header-safe identifier for an uploader.
     *
     * <p>A SHA-256 of the lowercased email rather than the email itself, for two
     * reasons. S3 user metadata rides in an HTTP header, so a non-ASCII address
     * (RFC 6532 allows them) would fail the <em>upload</em> — and breaking the
     * critical path to protect the cleanup path is the wrong trade. And it keeps
     * user email addresses out of bucket object metadata, which nothing needs;
     * the uploader is already in the application log if a human has to trace one.
     *
     * @return null for a blank email, so an unstamped object stays unstamped
     */
    public static String uploaderTag(String email) {
        if (email == null || email.isBlank()) return null;
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(email.trim().toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // every JRE ships it
        }
    }

    /**
     * Who uploaded the object behind this key or URL, for callers that need to
     * authorize a delete.
     *
     * <p>Read-only. This does NOT gate {@link #delete(String)} — server-initiated
     * cascades (deleting a post's photos with the post) legitimately remove
     * objects the acting user never uploaded, so the check belongs at the
     * request boundary, not here.</p>
     */
    public ObjectOwner ownerOf(String keyOrUrl) {
        String key = keyOrUrl == null ? null : PublicCdn.toObjectKey(keyOrUrl.trim());
        if (key == null || key.isBlank()) return new ObjectOwner(false, null);
        try {
            HeadObjectResponse head = s3.headObject(
                    HeadObjectRequest.builder().bucket(bucketName).key(key).build());
            Map<String, String> meta = head.metadata();
            return new ObjectOwner(true, meta == null ? null : meta.get(META_UPLOADER));
        } catch (NoSuchKeyException e) {
            return new ObjectOwner(false, null);
        } catch (S3Exception e) {
            // R2 answers HEAD-on-missing with a bare 404 rather than the typed
            // NoSuchKeyException the SDK models for GET, so match on status too.
            if (e.statusCode() == 404) return new ObjectOwner(false, null);
            log.error("R2 headObject failed for key={}: {}", key, e.getMessage());
            throw e;
        }
    }

    /**
     * Resize, compress, and upload. Returns the imageId (UUID), the object
     * key to store on the entity, and the public URL for delivery.
     *
     * @param uploaderEmail stamped onto the object (hashed — see
     *                      {@link #uploaderTag(String)}) so a later delete can be
     *                      authorized against it
     */
    public UploadResult upload(MultipartFile file, Scope scope, String uploaderEmail) throws IOException {
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

        putObject(key, optimized.bytes(), optimized.contentType(), uploaderTag(uploaderEmail));
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

    private void putObject(String key, byte[] content, String contentType, String uploaderTag) {
        String ct = (contentType == null || contentType.isBlank())
                ? "application/octet-stream" : contentType;
        try {
            PutObjectRequest.Builder b = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(ct)
                    .cacheControl("public, max-age=31536000, immutable");
            if (uploaderTag != null) b.metadata(Map.of(META_UPLOADER, uploaderTag));
            PutObjectRequest req = b.build();
            s3.putObject(req, RequestBody.fromBytes(content));
        } catch (Exception e) {
            log.error("R2 putObject failed for key={}: {}", key, e.getMessage());
            throw new RuntimeException("Failed to upload file to storage", e);
        }
    }
}
