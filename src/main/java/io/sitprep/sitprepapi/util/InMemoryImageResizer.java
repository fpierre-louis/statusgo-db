package io.sitprep.sitprepapi.util;

import org.imgscalr.Scalr;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;

public class InMemoryImageResizer {

    public static byte[] resizeImageFromUrl(String imageUrl, String format, int width, int height) throws IOException {
        // Fetch the image from the URL
        BufferedImage originalImage = ImageIO.read(new URL(imageUrl));
        if (originalImage == null) {
            throw new IOException("Failed to load image from URL: " + imageUrl);
        }

        // Resize the image
        BufferedImage resizedImage = Scalr.resize(originalImage, Scalr.Method.QUALITY, Scalr.Mode.FIT_EXACT, width, height);

        // Convert the resized image to a byte array
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(resizedImage, format, outputStream);

        return outputStream.toByteArray();
    }
}
