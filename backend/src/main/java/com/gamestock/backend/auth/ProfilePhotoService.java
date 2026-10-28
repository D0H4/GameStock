package com.gamestock.backend.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 사용자가 올린 프로필 사진. 받은 파일을 그대로 두지 않고 384px 정사각 JPEG로 다시 그려
 * 저장하므로 위치 정보 같은 메타데이터나 그림이 아닌 내용은 남지 않는다.
 */
@Service
public class ProfilePhotoService {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_SIDE = 4096;
    static final int SIZE = 384;

    private final JdbcTemplate jdbc;
    private final AuthService auth;

    public ProfilePhotoService(JdbcTemplate jdbc, AuthService auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    @Transactional
    public AuthService.Profile upload(long userId, byte[] body) {
        byte[] jpeg = normalize(body);
        String id = UUID.randomUUID().toString().replace("-", "");
        // 한 사람당 사진은 하나다. 새로 올리면 이전 사진 주소는 더 이상 열리지 않는다.
        jdbc.update("REPLACE INTO profile_photos (user_id, id, data) VALUES (?, ?, ?)", userId, id, jpeg);
        jdbc.update("UPDATE users SET profile_image_url = ?, profile_completed = TRUE WHERE id = ?", ProfileImages.photoUrl(id), userId);
        return auth.profile(userId);
    }

    public byte[] read(String id) {
        List<byte[]> rows = jdbc.query("SELECT data FROM profile_photos WHERE id = ?", (rs, row) -> rs.getBytes(1), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static byte[] normalize(byte[] body) {
        if (body == null || body.length == 0) throw new IllegalArgumentException("사진 파일이 비어 있습니다.");
        if (body.length > MAX_BYTES) throw new IllegalArgumentException("2MB 이하 사진만 올릴 수 있습니다.");
        BufferedImage source = decode(body);
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;

        BufferedImage square = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = square.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // 투명한 PNG는 JPEG로 바꾸면 검게 변하므로 흰 바탕 위에 그린다.
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, SIZE, SIZE);
            graphics.drawImage(source, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
        } finally {
            graphics.dispose();
        }
        return encodeJpeg(square);
    }

    private static BufferedImage decode(byte[] body) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(body))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) throw new IllegalArgumentException("JPG나 PNG 사진만 올릴 수 있습니다.");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("png")) throw new IllegalArgumentException("JPG나 PNG 사진만 올릴 수 있습니다.");
                reader.setInput(in, true, true);
                // 압축을 풀기 전에 크기를 본다. 작은 파일이 거대한 그림으로 풀려 메모리를 채우는 것을 막는다.
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE)
                    throw new IllegalArgumentException("가로세로 4096px 이하 사진만 올릴 수 있습니다.");
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException error) {
            if (error instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("사진을 읽지 못했습니다. 다른 파일을 골라 주세요.");
        }
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.88f);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException error) {
            throw new IllegalStateException("프로필 사진을 저장하지 못했습니다.", error);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
