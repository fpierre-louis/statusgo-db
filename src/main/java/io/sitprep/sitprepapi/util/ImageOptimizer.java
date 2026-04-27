package io.sitprep.sitprepapi.util;

import org.imgscalr.Scalr;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Server-side image resize + compress. Frontend resizes once before upload
 * (see {@code shared/utils/resizeImage.js}); this is the second pass that
 * makes the stored object the size the bucket actually wants.
 *
 * PNG path is preserved when the source has alpha (logos, screenshots);
 * everything else lands as JPEG.
 */
public final class ImageOptimizer {

    private ImageOptimizer() {}

    public record OptimizedImage(
            byte[] bytes,
            String extension,   // "jpg" or "png"
            String contentType, // "image/jpeg" or "image/png"
            int width,
            int height,
            boolean hasAlpha
    ) {}

    public static OptimizedImage optimize(BufferedImage input,
                                          int maxLongEdge,
                                          float jpegQuality,
                                          long maxPixels) throws IOException {
        if (input == null) throw new IOException("Invalid image");

        long pixels = (long) input.getWidth() * (long) input.getHeight();
        if (pixels > maxPixels) {
            throw new IOException("Image too large (pixel count exceeds limit)");
        }

        BufferedImage resized = resizeLongestEdge(input, maxLongEdge);
        boolean hasAlpha = resized.getColorModel() != null && resized.getColorModel().hasAlpha();

        if (hasAlpha) {
            byte[] png = encodePng(resized);
            return new OptimizedImage(png, "png", "image/png",
                    resized.getWidth(), resized.getHeight(), true);
        }
        byte[] jpg = encodeJpeg(resized, jpegQuality);
        return new OptimizedImage(jpg, "jpg", "image/jpeg",
                resized.getWidth(), resized.getHeight(), false);
    }

    public static BufferedImage resizeLongestEdge(BufferedImage src, int maxLongEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= maxLongEdge) return src;

        if (w >= h) {
            return Scalr.resize(src, Scalr.Method.AUTOMATIC, Scalr.Mode.FIT_TO_WIDTH,
                    maxLongEdge, Scalr.OP_ANTIALIAS);
        }
        return Scalr.resize(src, Scalr.Method.AUTOMATIC, Scalr.Mode.FIT_TO_HEIGHT,
                maxLongEdge, Scalr.OP_ANTIALIAS);
    }

    public static byte[] encodePng(BufferedImage img) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            if (!ImageIO.write(img, "png", baos)) {
                throw new IOException("PNG writer not available");
            }
            return baos.toByteArray();
        }
    }

    public static byte[] encodeJpeg(BufferedImage img, float quality) throws IOException {
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(img, 0, 0, null);

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                if (!ImageIO.write(rgb, "jpg", baos)) {
                    throw new IOException("JPEG writer not available");
                }
                return baos.toByteArray();
            }
        }

        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {

            writer.setOutput(ios);

            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                float q = Math.max(0.1f, Math.min(quality, 1.0f));
                param.setCompressionQuality(q);
            }

            writer.write(null, new IIOImage(rgb, null, null), param);
            writer.dispose();
            return baos.toByteArray();
        } finally {
            try { writer.dispose(); } catch (Exception ignored) {}
        }
    }
}
