package ai.inquery.server.web.api.controller.config;

import ai.inquery.server.domain.core.reference.ReferenceDocumentService;
import ai.inquery.server.domain.repository.entity.ReferenceDocumentDO;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.common.util.ContextUtils;
import ai.inquery.server.web.api.service.ReferenceDocumentVectorIndexer;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI Integration reference documents — upload, list, download, delete.
 */
@Slf4j
@RestController
@RequestMapping("/api/config/ai/documents")
public class ReferenceDocumentController {

    private static final String ERR_UNAUTHORIZED = "auth.unauthorized";
    private static final String ERR_BAD_REQUEST = "common.badRequest";
    private static final String ERR_NOT_FOUND = "common.notFound";

    @Autowired
    private ReferenceDocumentService referenceDocumentService;

    @Autowired
    private ReferenceDocumentVectorIndexer vectorIndexer;

    @PostMapping
    public DataResult<ReferenceDocumentMetaDTO> upload(
            @RequestParam("file") MultipartFile file) {
        Long userId = ContextUtils.getUserId();
        if (userId == null) {
            return DataResult.error(ERR_UNAUTHORIZED, "Unauthorized");
        }
        try {
            ReferenceDocumentDO row = referenceDocumentService.upload(
                    userId,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes());
            vectorIndexer.indexDocument(row);
            ReferenceDocumentDO refreshed = referenceDocumentService.findMeta(userId, row.getId());
            return DataResult.of(ReferenceDocumentMetaDTO.from(refreshed != null ? refreshed : row));
        } catch (IllegalArgumentException e) {
            log.warn("Reference document upload rejected: {}", e.getMessage());
            return DataResult.error(ERR_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("Reference document upload failed", e);
            return DataResult.error(ERR_BAD_REQUEST, "Upload failed: " + e.getMessage());
        }
    }

    @GetMapping
    public DataResult<ReferenceDocumentListDTO> list() {
        Long userId = ContextUtils.getUserId();
        if (userId == null) {
            return DataResult.error(ERR_UNAUTHORIZED, "Unauthorized");
        }
        List<ReferenceDocumentMetaDTO> docs = referenceDocumentService.listMeta(userId).stream()
                .map(ReferenceDocumentMetaDTO::from)
                .collect(Collectors.toList());
        ReferenceDocumentListDTO payload = new ReferenceDocumentListDTO();
        payload.setDocuments(docs);
        payload.setUsedBytes(referenceDocumentService.usedBytes(userId));
        payload.setQuotaBytes(ReferenceDocumentService.MAX_USER_QUOTA_BYTES);
        return DataResult.of(payload);
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        Long userId = ContextUtils.getUserId();
        if (userId == null) return ResponseEntity.status(401).build();

        ReferenceDocumentDO meta = referenceDocumentService.findMeta(userId, id);
        if (meta == null) return ResponseEntity.notFound().build();

        byte[] bytes = referenceDocumentService.loadContent(userId, id);
        if (bytes == null) return ResponseEntity.notFound().build();

        String encoded = URLEncoder.encode(meta.getFilename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(meta.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .body(bytes);
    }

    @DeleteMapping("/{id}")
    public ActionResult delete(@PathVariable Long id) {
        Long userId = ContextUtils.getUserId();
        if (userId == null) {
            return ActionResult.fail(ERR_UNAUTHORIZED, "Unauthorized", null);
        }

        ReferenceDocumentDO meta = referenceDocumentService.findMeta(userId, id);
        if (meta == null) {
            return ActionResult.fail(ERR_NOT_FOUND, "Not found", null);
        }

        int chunkCount = meta.getChunkCount() != null ? meta.getChunkCount() : 0;
        vectorIndexer.deleteVectors(id, chunkCount);

        boolean ok = referenceDocumentService.delete(userId, id);
        return ok ? ActionResult.isSuccess()
                : ActionResult.fail(ERR_NOT_FOUND, "Not found", null);
    }

    @PostMapping("/{id}/reindex")
    public DataResult<ReferenceDocumentMetaDTO> reindex(@PathVariable Long id) {
        Long userId = ContextUtils.getUserId();
        if (userId == null) {
            return DataResult.error(ERR_UNAUTHORIZED, "Unauthorized");
        }

        ReferenceDocumentDO meta = referenceDocumentService.findMeta(userId, id);
        if (meta == null) {
            return DataResult.error(ERR_NOT_FOUND, "Not found");
        }

        int chunkCount = meta.getChunkCount() != null ? meta.getChunkCount() : 0;
        if (chunkCount > 0) {
            vectorIndexer.deleteVectors(id, chunkCount);
        }
        vectorIndexer.indexDocument(meta);

        ReferenceDocumentDO refreshed = referenceDocumentService.findMeta(userId, id);
        return DataResult.of(ReferenceDocumentMetaDTO.from(refreshed != null ? refreshed : meta));
    }

    @Data
    public static class ReferenceDocumentListDTO {
        private List<ReferenceDocumentMetaDTO> documents;
        private long usedBytes;
        private long quotaBytes;
    }

    @Data
    public static class ReferenceDocumentMetaDTO {
        private Long id;
        private String filename;
        private String mimeType;
        private String kind;
        private Long sizeBytes;
        private String indexStatus;
        private String indexError;
        private Integer chunkCount;
        private Date gmtCreate;

        static ReferenceDocumentMetaDTO from(ReferenceDocumentDO row) {
            ReferenceDocumentMetaDTO dto = new ReferenceDocumentMetaDTO();
            dto.setId(row.getId());
            dto.setFilename(row.getFilename());
            dto.setMimeType(row.getMimeType());
            dto.setKind(row.getKind());
            dto.setSizeBytes(row.getSizeBytes());
            dto.setIndexStatus(row.getIndexStatus());
            dto.setIndexError(row.getIndexError());
            dto.setChunkCount(row.getChunkCount());
            dto.setGmtCreate(row.getGmtCreate());
            return dto;
        }
    }
}
