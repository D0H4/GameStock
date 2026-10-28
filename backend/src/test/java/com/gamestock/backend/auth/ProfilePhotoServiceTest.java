package com.gamestock.backend.auth;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ProfilePhotoServiceTest {
    private static byte[] image(int width, int height, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, width / 2, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(format.equals("png") ? image : toRgb(image), format, out);
        return out.toByteArray();
    }

    private static BufferedImage toRgb(BufferedImage source) {
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.createGraphics().drawImage(source, 0, 0, null);
        return rgb;
    }

    @Test void redrawsAnyAcceptedPhotoAsSquareJpeg() throws Exception {
        for (byte[] upload : new byte[][] { image(800, 500, "png"), image(300, 900, "jpeg") }) {
            byte[] stored = ProfilePhotoService.normalize(upload);
            assertEquals((byte) 0xFF, stored[0]);
            assertEquals((byte) 0xD8, stored[1]);
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stored));
            assertEquals(ProfilePhotoService.SIZE, decoded.getWidth());
            assertEquals(ProfilePhotoService.SIZE, decoded.getHeight());
        }
    }

    @Test void transparentPixelsBecomeWhiteInsteadOfBlack() throws Exception {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(ProfilePhotoService.normalize(image(400, 400, "png"))));
        Color right = new Color(decoded.getRGB(ProfilePhotoService.SIZE - 10, ProfilePhotoService.SIZE / 2));
        assertTrue(right.getRed() > 240 && right.getGreen() > 240 && right.getBlue() > 240);
    }

    @Test void rejectsFilesThatAreNotJpegOrPng() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ProfilePhotoService.normalize(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> ProfilePhotoService.normalize("<svg onload=alert(1)>".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> ProfilePhotoService.normalize(image(40, 40, "gif")));
    }

    @Test void rejectsOversizedFilesAndDimensions() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ProfilePhotoService.normalize(new byte[ProfilePhotoService.MAX_BYTES + 1]));
        var error = assertThrows(IllegalArgumentException.class, () -> ProfilePhotoService.normalize(image(ProfilePhotoService.MAX_SIDE + 1, 2, "png")));
        assertTrue(error.getMessage().contains("4096"));
    }
}
