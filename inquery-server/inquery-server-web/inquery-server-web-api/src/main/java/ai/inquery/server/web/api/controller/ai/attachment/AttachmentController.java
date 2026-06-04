package ai.inquery.server.web.api.controller.ai.attachment;

import ai.inquery.server.domain.core.attachment.AiChatAttachmentService;
import ai.inquery.server.domain.core.attachment.ModelCapabilities;
import ai.inquery.server.domain.repository.entity.AiChatAttachmentDO;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.common.util.ContextUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoints for AI chat attachments (upload / download /
 * thumbnail / room library / soft-delete) plus the per-model
 * capability matrix the UI uses for pre-send guards.
 *
 * <p>All routes require an authenticated user — every read enforces
 * {@code attachment.user_id == ContextUtils.getUserId()} via the
 * service layer.
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/attachments")
public class AttachmentController {

    @Autowired
    private AiChatAttachmentService attachmentService;

    // ---------------------------------------------------------------
    // Upload
    // ---------------------------------------------------------------

    private static final String ERR_UNAUTHORIZED = "auth.unauthorized";
    private static final String ERR_BAD_REQUEST  = "common.badRequest";
    private static final String ERR_NOT_FOUND    = "common.notFound";

    @PostMapping
    public DataResult<AttachmentMetaDTO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chatRoomId", required = false) Long chatRoomId) {
        Long userId = ContextUtils.getUserId();
        if (userId == null) {
            return DataResult.error(ERR_UNAUTHORIZED, "Unauthorized");
        }
        try {
            AiChatAttachmentDO row = attachmentService.upload(
                    userId, chatRoomId,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes());
            return DataResult.of(AttachmentMetaDTO.from(row));
        } catch (IllegalArgumentException e) {
            log.warn("Attachment upload rejected: {}", e.getMessage());
            return DataResult.error(ERR_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("Attachment upload failed", e);
            return DataResult.error(ERR_BAD_REQUEST, "Upload failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------

    @GetMapping("/{id}/meta")
    public DataResult<AttachmentMetaDTO> getMeta(@PathVariable Long id) {
        Long userId = ContextUtils.getUserId();
        if (userId == null) return DataResult.error(ERR_UNAUTHORIZED, "Unauthorized");
        AiChatAttachmentDO row = attachmentService.findMeta(userId, id);
        if (row == null) return DataResult.error(ERR_NOT_FOUND, "Not found");
        return DataResult.of(AttachmentMetaDTO.from(row));
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        Long userId = ContextUtils.getUserId();
        if (userId == null) return ResponseEntity.status(401).build();
        AiChatAttachmentDO meta = attachmentService.findMeta(userId, id);
        if (meta == null) return ResponseEntity.notFound().build();
        byte[] bytes = attachmentService.loadContent(userId, id);
        if (bytes == null) return ResponseEntity.notFound().build();

        // RFC 5987 encoding so non-ASCII filenames survive the round trip.
        String encoded = URLEncoder.encode(meta.getFilename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(meta.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encoded)
                .body(bytes);
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable Long id) {
        Long userId = ContextUtils.getUserId();
        if (userId == null) return ResponseEntity.status(401).build();
        AiChatAttachmentDO meta = attachmentService.findMeta(userId, id);
        if (meta == null || meta.getThumbnailMime() == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = attachmentService.loadThumbnail(userId, id);
        if (bytes == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(meta.getThumbnailMime()))
                .body(bytes);
    }

    /** Room library — meta only, newest first. */
    @GetMapping
    public DataResult<List<AttachmentMetaDTO>> listForRoom(@RequestParam("roomId") Long roomId) {
        Long userId = ContextUtils.getUserId();
        if (userId == null) return DataResult.error(ERR_UNAUTHORIZED, "Unauthorized");
        List<AttachmentMetaDTO> dtos = attachmentService.listForRoom(userId, roomId).stream()
                .map(AttachmentMetaDTO::from)
                .collect(Collectors.toList());
        return DataResult.of(dtos);
    }

    @DeleteMapping("/{id}")
    public ActionResult delete(@PathVariable Long id) {
        Long userId = ContextUtils.getUserId();
        if (userId == null) return ActionResult.fail(ERR_UNAUTHORIZED, "Unauthorized", null);
        boolean ok = attachmentService.softDelete(userId, id);
        return ok ? ActionResult.isSuccess() : ActionResult.fail(ERR_NOT_FOUND, "Not found", null);
    }

    // ---------------------------------------------------------------
    // Model capability projection for the input UI
    // ---------------------------------------------------------------

    @GetMapping("/capabilities")
    public DataResult<Map<String, List<String>>> capabilities() {
        Map<String, List<String>> projected = ModelCapabilities.matrix().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .map(Enum::name)
                                .collect(Collectors.toList())));
        return DataResult.of(projected);
    }
}
