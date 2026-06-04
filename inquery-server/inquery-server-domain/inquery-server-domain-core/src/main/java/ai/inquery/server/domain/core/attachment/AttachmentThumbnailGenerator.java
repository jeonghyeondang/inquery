package ai.inquery.server.domain.core.attachment;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Builds a 320px-wide PNG preview for chat attachments.
 *
 * <ul>
 *     <li>Image → decode + downscale (skips re-encoding if the source is
 *         already small enough).</li>
 *     <li>PDF / office docs / text → no thumbnail; the UI renders a
 *         per-format file icon, which the team prefers visually over
 *         a tiny first-page preview.</li>
 * </ul>
 *
 * <p>Returns {@code null} on failure rather than throwing; the
 * attachment is still usable, just without a preview.
 */
@Slf4j
public final class AttachmentThumbnailGenerator {

    private static final int TARGET_WIDTH = 320;

    public static final String THUMBNAIL_MIME = "image/png";

    private AttachmentThumbnailGenerator() {}

    public static Thumbnail generate(String kind, byte[] bytes) {
        try {
            if ("image".equals(kind)) {
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
                if (src == null) return null;
                if (src.getWidth() <= TARGET_WIDTH) {
                    return encode(src);
                }
                return encode(downscale(src));
            }
            return null;
        } catch (Exception e) {
            log.warn("Thumbnail generation failed (kind={}, size={}): {}",
                    kind, bytes == null ? 0 : bytes.length, e.getMessage());
            return null;
        }
    }

    /** Bicubic downscale to TARGET_WIDTH, preserves aspect ratio. */
    private static BufferedImage downscale(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= TARGET_WIDTH) return src;
        int targetH = Math.max(1, (int) Math.round(((double) h * TARGET_WIDTH) / w));

        BufferedImage out = new BufferedImage(TARGET_WIDTH, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, TARGET_WIDTH, targetH, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static Thumbnail encode(BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        ImageIO.write(img, "PNG", baos);
        return new Thumbnail(baos.toByteArray(), THUMBNAIL_MIME);
    }

    public record Thumbnail(byte[] bytes, String mime) {}
}
