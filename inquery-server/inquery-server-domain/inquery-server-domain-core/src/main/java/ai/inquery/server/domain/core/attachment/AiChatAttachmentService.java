package ai.inquery.server.domain.core.attachment;

import ai.inquery.server.domain.core.attachment.AttachmentThumbnailGenerator.Thumbnail;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.AiChatAttachmentDO;
import ai.inquery.server.domain.repository.mapper.AiChatAttachmentMapper;
import ai.inquery.server.domain.repository.mapper.AiChatMessageAttachmentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * CRUD for {@link AiChatAttachmentDO} plus the synchronous side effects
 * (text extraction + thumbnail generation) that run at upload time.
 *
 * <p>Authorization is enforced at every read: callers must pass the
 * current {@code userId} and we drop rows owned by anyone else. The
 * controller layer takes care of pulling the user id from
 * {@code ContextUtils.getUserId()}.
 */
@Slf4j
@Service
public class AiChatAttachmentService {

    /** Hard upload cap. Matches Spring multipart.max-file-size guidance. */
    public static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;

    /** Soft per-message cap surfaced to the UI; enforced at chat-time. */
    public static final int MAX_ATTACHMENTS_PER_MESSAGE = 5;

    private AiChatAttachmentMapper mapper() {
        return Dbutils.getMapper(AiChatAttachmentMapper.class);
    }

    private AiChatMessageAttachmentMapper linkMapper() {
        return Dbutils.getMapper(AiChatMessageAttachmentMapper.class);
    }

    // ---------------------------------------------------------------
    // Upload
    // ---------------------------------------------------------------

    /**
     * Persist an uploaded file. Extracts text + builds a thumbnail
     * synchronously so the UI can render the preview as soon as the
     * upload completes.
     *
     * @throws IllegalArgumentException for oversize / unsupported MIME /
     *                                  empty payloads
     */
    public AiChatAttachmentDO upload(Long userId, Long chatRoomId,
                                     String filename, String declaredMime,
                                     byte[] content) {
        if (userId == null) {
            throw new IllegalArgumentException("Missing user");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Empty file");
        }
        if (content.length > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "File exceeds " + (MAX_FILE_SIZE_BYTES / (1024 * 1024)) + "MB limit");
        }

        String safeName = sanitizeFilename(filename);
        String resolvedMime = resolveMime(safeName, declaredMime, content);
        String kind = kindOf(resolvedMime, safeName);
        if (kind == null) {
            throw new IllegalArgumentException("Unsupported file type: " + resolvedMime);
        }

        String extracted = AttachmentTextExtractor.extract(kind, resolvedMime, content);
        Thumbnail thumb = AttachmentThumbnailGenerator.generate(kind, content);

        AiChatAttachmentDO row = new AiChatAttachmentDO();
        row.setUserId(userId);
        row.setChatRoomId(chatRoomId);
        row.setFilename(safeName);
        row.setMimeType(resolvedMime);
        row.setSizeBytes((long) content.length);
        row.setKind(kind);
        row.setContent(content);
        row.setExtractedText(extracted);
        if (thumb != null) {
            row.setThumbnailContent(thumb.bytes());
            row.setThumbnailMime(thumb.mime());
        }
        row.setDeleted("n");
        mapper().insert(row);
        log.info("Attachment uploaded: id={}, user={}, room={}, kind={}, size={}B, hasExtractedText={}, hasThumb={}",
                row.getId(), userId, chatRoomId, kind, content.length,
                extracted != null, thumb != null);
        return row;
    }

    // ---------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------

    /** Meta only (no BYTEA/TEXT columns). Returns null if not found / not owned. */
    public AiChatAttachmentDO findMeta(Long userId, Long id) {
        if (id == null || userId == null) return null;
        AiChatAttachmentDO row = mapper().selectById(id);
        if (row == null) return null;
        if (!userId.equals(row.getUserId())) return null;
        if ("y".equalsIgnoreCase(row.getDeleted())) return null;
        return row;
    }

    public byte[] loadContent(Long userId, Long id) {
        if (findMeta(userId, id) == null) return null;
        AiChatAttachmentDO row = mapper().selectContentRowById(id);
        return row == null ? null : row.getContent();
    }

    public byte[] loadThumbnail(Long userId, Long id) {
        if (findMeta(userId, id) == null) return null;
        AiChatAttachmentDO row = mapper().selectThumbnailRowById(id);
        return row == null ? null : row.getThumbnailContent();
    }

    public String loadExtractedText(Long userId, Long id) {
        if (findMeta(userId, id) == null) return null;
        return mapper().selectExtractedTextById(id);
    }

    /** Same as {@link #loadExtractedText} but trusted-internal: no user check. */
    public String loadExtractedTextInternal(Long id) {
        return mapper().selectExtractedTextById(id);
    }

    /** Same as {@link #loadContent} but trusted-internal: no user check. */
    public byte[] loadContentInternal(Long id) {
        AiChatAttachmentDO row = mapper().selectContentRowById(id);
        return row == null ? null : row.getContent();
    }

    /** Room library — meta only, newest first. */
    public List<AiChatAttachmentDO> listForRoom(Long userId, Long roomId) {
        if (userId == null || roomId == null) return Collections.emptyList();
        LambdaQueryWrapper<AiChatAttachmentDO> q = new LambdaQueryWrapper<>();
        q.eq(AiChatAttachmentDO::getUserId, userId)
                .eq(AiChatAttachmentDO::getChatRoomId, roomId)
                .ne(AiChatAttachmentDO::getDeleted, "y")
                .orderByDesc(AiChatAttachmentDO::getId);
        return mapper().selectList(q);
    }

    /** Bulk meta fetch (for hydrating message → attachments JOIN). */
    public List<AiChatAttachmentDO> findMetaByIds(Long userId, List<Long> ids) {
        if (userId == null || ids == null || ids.isEmpty()) return Collections.emptyList();
        LambdaQueryWrapper<AiChatAttachmentDO> q = new LambdaQueryWrapper<>();
        q.in(AiChatAttachmentDO::getId, ids)
                .eq(AiChatAttachmentDO::getUserId, userId)
                .ne(AiChatAttachmentDO::getDeleted, "y");
        return mapper().selectList(q);
    }

    // ---------------------------------------------------------------
    // Updates
    // ---------------------------------------------------------------

    public boolean softDelete(Long userId, Long id) {
        AiChatAttachmentDO existing = findMeta(userId, id);
        if (existing == null) return false;
        LambdaUpdateWrapper<AiChatAttachmentDO> u = new LambdaUpdateWrapper<>();
        u.eq(AiChatAttachmentDO::getId, id)
                .eq(AiChatAttachmentDO::getUserId, userId)
                .set(AiChatAttachmentDO::getDeleted, "y");
        return mapper().update(null, u) > 0;
    }

    /**
     * Set / refresh the chat_room_id when an attachment was uploaded
     * before the user had picked a room. Idempotent.
     */
    public void bindToRoom(Long userId, Long id, Long roomId) {
        if (id == null || roomId == null) return;
        LambdaUpdateWrapper<AiChatAttachmentDO> u = new LambdaUpdateWrapper<>();
        u.eq(AiChatAttachmentDO::getId, id)
                .eq(AiChatAttachmentDO::getUserId, userId)
                .set(AiChatAttachmentDO::getChatRoomId, roomId);
        mapper().update(null, u);
    }

    // ---------------------------------------------------------------
    // Message linkage (N:N)
    // ---------------------------------------------------------------

    /**
     * Replace all attachments linked to {@code messageId} with the
     * supplied ordered list. Used both at message-create time and when
     * the user re-attaches files to an existing message.
     */
    public void linkAttachmentsToMessage(Long messageId, List<Long> attachmentIds) {
        if (messageId == null) return;
        AiChatMessageAttachmentMapper m = linkMapper();
        m.deleteByMessageId(messageId);
        if (attachmentIds == null || attachmentIds.isEmpty()) return;
        for (int i = 0; i < attachmentIds.size(); i++) {
            Long aid = attachmentIds.get(i);
            if (aid == null) continue;
            m.insert(messageId, aid, i);
        }
    }

    public List<Long> findAttachmentIdsForMessage(Long messageId) {
        if (messageId == null) return Collections.emptyList();
        return linkMapper().findAttachmentIdsByMessageId(messageId);
    }

    // ---------------------------------------------------------------
    // Helpers — kept private. Magic-byte sniffing only for image/pdf;
    // text-family files are validated by extension because there's no
    // single byte signature for "is this UTF-8 text".
    // ---------------------------------------------------------------

    private static final List<String> ALLOWED_TEXT_EXTENSIONS =
            List.of(".txt", ".md", ".markdown", ".log", ".csv", ".tsv",
                    ".json", ".sql", ".yaml", ".yml", ".xml");

    /**
     * Returns one of {@code "image" | "pdf" | "office" | "text"} or
     * {@code null} if unsupported.
     *
     * <p>{@code office} covers OOXML formats (pptx / docx / xlsx) — we
     * extract their text via Apache POI at upload time and ride that
     * extracted text to the LLM as a TextContent. The legacy binary
     * formats (.ppt / .doc / .xls) are intentionally not covered —
     * they need poi-scratchpad which we don't pull in.
     */
    private static String kindOf(String mime, String filename) {
        if (mime == null) return null;
        String m = mime.toLowerCase(Locale.ROOT);
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);

        // Vision models (OpenAI / Claude / Gemini) only accept raster
        // image inputs — PNG / JPEG / GIF / WebP. SVG is XML text, and
        // formats like TIFF / BMP / HEIC aren't broadly supported. Send
        // raster images as image; route SVG/XML-flavoured images to
        // text so the model reads the markup directly.
        if (m.equals("image/png") || m.equals("image/jpeg") || m.equals("image/jpg")
                || m.equals("image/gif") || m.equals("image/webp")) {
            return "image";
        }
        if (m.equals("image/svg+xml") || m.equals("image/svg")) {
            return "text";
        }
        if (m.startsWith("image/")) {
            // Unknown image flavour (TIFF / BMP / HEIC / AVIF…). Reject
            // rather than ship it to the LLM and watch the API throw,
            // unless the filename hints at SVG which we already handled
            // above. Letting it through as-is is worse than failing fast.
            return null;
        }
        if (m.equals("application/pdf")) return "pdf";

        // OOXML office formats. Browsers reliably send these MIME types;
        // we still keep extension fallback for octet-stream / drag from
        // some apps that drop the Content-Type.
        if (m.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation")
                || m.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                || m.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) {
            return "office";
        }
        if (lower.endsWith(".pptx") || lower.endsWith(".docx") || lower.endsWith(".xlsx")) {
            return "office";
        }

        if (m.startsWith("text/")) return "text";
        // Some browsers send application/json, application/sql etc.
        if (m.equals("application/json") || m.equals("application/xml")
                || m.equals("application/x-yaml") || m.equals("application/sql")) {
            return "text";
        }
        // Fallback by extension for text-family files when the browser
        // sent application/octet-stream.
        for (String ext : ALLOWED_TEXT_EXTENSIONS) {
            if (lower.endsWith(ext)) return "text";
        }
        if (lower.endsWith(".svg")) return "text";
        return null;
    }

    /**
     * Resolve the effective MIME type. Order:
     * <ol>
     *     <li>Magic bytes for image/PDF — most trustworthy.</li>
     *     <li>Browser-declared Content-Type, if non-empty and not
     *         the useless {@code application/octet-stream} default.</li>
     *     <li>Filename extension fallback (mostly for text files where
     *         the browser shrugged).</li>
     * </ol>
     */
    private static String resolveMime(String filename, String declaredMime, byte[] bytes) {
        String sniffed = sniffMagicBytes(bytes);
        if (sniffed != null) return sniffed;
        if (declaredMime != null && !declaredMime.isBlank()
                && !"application/octet-stream".equalsIgnoreCase(declaredMime)) {
            return declaredMime.toLowerCase(Locale.ROOT);
        }
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf"))   return "application/pdf";
        if (lower.endsWith(".png"))   return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))   return "image/gif";
        if (lower.endsWith(".webp"))  return "image/webp";
        if (lower.endsWith(".pptx"))  return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".docx"))  return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx"))  return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".json"))  return "application/json";
        if (lower.endsWith(".csv"))   return "text/csv";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".sql"))   return "application/sql";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "application/x-yaml";
        if (lower.endsWith(".xml"))   return "application/xml";
        if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".tsv")) {
            return "text/plain";
        }
        return declaredMime != null ? declaredMime : "application/octet-stream";
    }

    /** Return canonical MIME if bytes match a known image/PDF signature. */
    private static String sniffMagicBytes(byte[] b) {
        if (b == null || b.length < 4) return null;
        // %PDF
        if (b[0] == 0x25 && b[1] == 0x50 && b[2] == 0x44 && b[3] == 0x46) {
            return "application/pdf";
        }
        // PNG  89 50 4E 47
        if (b[0] == (byte) 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
            return "image/png";
        }
        // JPEG FF D8 FF
        if (b[0] == (byte) 0xFF && b[1] == (byte) 0xD8 && b[2] == (byte) 0xFF) {
            return "image/jpeg";
        }
        // GIF87a / GIF89a
        if (b.length >= 6
                && b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x38
                && (b[4] == 0x37 || b[4] == 0x39) && b[5] == 0x61) {
            return "image/gif";
        }
        // WebP: "RIFF" .... "WEBP"
        if (b.length >= 12
                && b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46
                && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50) {
            return "image/webp";
        }
        return null;
    }

    private static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) return "untitled";
        // Strip any path component the browser might have sent (IE).
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        // Drop control / non-printable chars; keep unicode letters.
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 0x20 && c != 0x7F) sb.append(c);
        }
        String cleaned = sb.toString().strip();
        if (cleaned.isEmpty()) cleaned = "untitled";
        // Cap length to fit the VARCHAR(512) column comfortably under
        // UTF-8 worst case (~4 bytes/char).
        if (cleaned.getBytes(StandardCharsets.UTF_8).length > 480) {
            cleaned = cleaned.substring(0, Math.min(cleaned.length(), 120));
        }
        return cleaned;
    }
}
