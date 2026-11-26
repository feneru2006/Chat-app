package com.zalo.server.util;

import com.zalo.common.Protocol;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/** Tạo ảnh thu nhỏ tối đa 300x300px (~20KB) cho tiêu chí Image. */
public final class Thumbnailer {
    private Thumbnailer() {}

    /** @return true nếu tạo thành công (file không phải ảnh đọc được thì trả false). */
    public static boolean create(Path src, Path dest) {
        try {
            System.setProperty("java.awt.headless", "true");
            BufferedImage img = ImageIO.read(src.toFile());
            if (img == null) return false;
            double scale = Math.min(1.0, Math.min((double) Protocol.THUMB_MAX_PX / img.getWidth(),
                    (double) Protocol.THUMB_MAX_PX / img.getHeight()));
            int w = Math.max(1, (int) (img.getWidth() * scale));
            int h = Math.max(1, (int) (img.getHeight() * scale));
            BufferedImage th = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);   // JPG không có alpha
            Graphics2D g = th.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, w, h, java.awt.Color.WHITE, null);
            g.dispose();
            return ImageIO.write(th, "jpg", dest.toFile());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
