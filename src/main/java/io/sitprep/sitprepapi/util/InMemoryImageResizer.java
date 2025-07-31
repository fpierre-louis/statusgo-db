package io.sitprep.sitprepapi.util;

import org.imgscalr.Scalr;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream; // Needed for try-with-resources
import java.net.URL; // Keep this import for toURL() method
import java.net.URI; // ✅ NEW: Import URI
import java.net.URISyntaxException; // ✅ NEW: Import URISyntaxException

public class InMemoryImageResizer {

    public static byte[] resizeImageFromUrl(String imageUrl, String format, int width, int height) throws IOException {
        try (InputStream is = new URI(imageUrl).toURL().openStream()) { // ✅ FIX: Use URI to avoid deprecation and handle properly
            BufferedImage originalImage = ImageIO.read(is);
            if (originalImage == null) {
                throw new IOException("Failed to load image from URL: " + imageUrl);
            }

            // Resize the image - using Scalr.Method.AUTOMATIC and Scalr.Mode.FIT_TO_WIDTH is generally more robust
            // Scalr.Method.QUALITY is good, but AUTOMATIC often handles scaling better for general cases.
            // FIT_EXACT forces exact dimensions, which can distort aspect ratio. FIT_TO_WIDTH maintains aspect ratio.
            BufferedImage resizedImage = Scalr.resize(originalImage, Scalr.Method.AUTOMATIC, Scalr.Mode.FIT_TO_WIDTH, width, height, Scalr.OP_ANTIALIAS);


            // Convert the resized image to a byte array
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(resizedImage, format, outputStream);

            return outputStream.toByteArray();
        } catch (URISyntaxException e) { // ✅ NEW: Catch URISyntaxException
            throw new IOException("Invalid URL syntax for image: " + imageUrl, e);
        }
    }
}