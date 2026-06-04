package ai.inquery.server.domain.core.reference;

import ai.inquery.server.domain.core.attachment.AttachmentTextExtractor;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.ReferenceDocumentDO;
import ai.inquery.server.domain.repository.mapper.ReferenceDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * CRUD for AI Integration reference documents. Persists originals in PostgreSQL
 * (small files) or {@code ~/.inquery/documents/} (large files).
 */
@Slf4j
@Service
public class ReferenceDocumentService {

    public static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;
    public static final long MAX_USER_QUOTA_BYTES = 5L * 1024 * 1024 * 1024;
    public static final long INLINE_BYTEA_THRESHOLD = 20L * 1024 * 1024;

    public static final String STORAGE_DB = "db";
    public static final String STORAGE_FILE = "file";

    private static final String DOCUMENTS_DIR =
            System.getProperty("user.home") + File.separator + ".inquery" + File.separator + "documents";

    private ReferenceDocumentMapper mapper() {
        return Dbutils.getMapper(ReferenceDocumentMapper.class);
    }

    public ReferenceDocumentDO upload(Long userId, String filename, String declaredMime, byte[] content) {
        if (userId == null) {
            throw new IllegalArgumentException("Missing user");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Empty file");
        }
        if (content.length > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File exceeds 50MB limit");
        }

        Long used = mapper().sumSizeBytesByUser(userId);
        if (used != null && used + content.length > MAX_USER_QUOTA_BYTES) {
            throw new IllegalArgumentException("Storage quota exceeded (5GB limit)");
        }

        String safeName = sanitizeFilename(filename);
        String resolvedMime = resolveMime(safeName, declaredMime, content);
        String kind = kindOf(resolvedMime, safeName);
        if (kind == null) {
            throw new IllegalArgumentException("Unsupported file type: " + resolvedMime);
        }

        String hash = sha256Hex(content);
        if (findByHash(userId, hash) != null) {
            throw new IllegalArgumentException("This file was already uploaded");
        }

        String extracted = AttachmentTextExtractor.extractForReference(kind, resolvedMime, content);
        if (extracted == null || extracted.isBlank()) {
            throw new IllegalArgumentException("No text could be extracted from this file");
        }

        ReferenceDocumentDO row = new ReferenceDocumentDO();
        row.setUserId(userId);
        row.setFilename(safeName);
        row.setMimeType(resolvedMime);
        row.setKind(kind);
        row.setSizeBytes((long) content.length);
        row.setFileHash(hash);
        row.setExtractedText(extracted);
        row.setChunkCount(0);
        row.setIndexStatus("pending");
        row.setDeleted("n");

        if (content.length <= INLINE_BYTEA_THRESHOLD) {
            row.setStorageType(STORAGE_DB);
            row.setContent(content);
        } else {
            row.setStorageType(STORAGE_FILE);
            row.setContent(null);
        }

        mapper().insert(row);

        if (STORAGE_FILE.equals(row.getStorageType())) {
            try {
                Path path = storeOnDisk(row.getId(), safeName, content);
                row.setStoragePath(path.toString());
                mapper().updateById(row);
            } catch (IOException e) {
                mapper().deleteById(row.getId());
                throw new IllegalArgumentException("Failed to store file: " + e.getMessage());
            }
        }

        log.info("Reference document uploaded: id={}, user={}, kind={}, size={}B, storage={}",
                row.getId(), userId, kind, content.length, row.getStorageType());
        return row;
    }

    public List<ReferenceDocumentDO> listMeta(Long userId) {
        if (userId == null) return Collections.emptyList();
        LambdaQueryWrapper<ReferenceDocumentDO> q = new LambdaQueryWrapper<>();
        q.eq(ReferenceDocumentDO::getUserId, userId)
                .ne(ReferenceDocumentDO::getDeleted, "y")
                .orderByDesc(ReferenceDocumentDO::getId);
        return mapper().selectList(q);
    }

    public long usedBytes(Long userId) {
        Long sum = mapper().sumSizeBytesByUser(userId);
        return sum != null ? sum : 0L;
    }

    public ReferenceDocumentDO findMeta(Long userId, Long id) {
        if (userId == null || id == null) return null;
        ReferenceDocumentDO row = mapper().selectById(id);
        if (row == null || !userId.equals(row.getUserId())) return null;
        if ("y".equalsIgnoreCase(row.getDeleted())) return null;
        return row;
    }

    public byte[] loadContent(Long userId, Long id) {
        ReferenceDocumentDO meta = findMeta(userId, id);
        if (meta == null) return null;
        return loadContentInternal(meta);
    }

    public byte[] loadContentInternal(ReferenceDocumentDO meta) {
        if (meta == null) return null;
        if (STORAGE_DB.equals(meta.getStorageType())) {
            ReferenceDocumentDO row = mapper().selectContentRowById(meta.getId());
            return row != null ? row.getContent() : null;
        }
        if (STORAGE_FILE.equals(meta.getStorageType()) && meta.getStoragePath() != null) {
            try {
                return Files.readAllBytes(Path.of(meta.getStoragePath()));
            } catch (IOException e) {
                log.error("Failed to read reference document file: {}", meta.getStoragePath(), e);
                return null;
            }
        }
        return null;
    }

    public boolean delete(Long userId, Long id) {
        ReferenceDocumentDO meta = findMeta(userId, id);
        if (meta == null) return false;

        if (STORAGE_FILE.equals(meta.getStorageType()) && meta.getStoragePath() != null) {
            try {
                Files.deleteIfExists(Path.of(meta.getStoragePath()));
            } catch (IOException e) {
                log.warn("Failed to delete file {}: {}", meta.getStoragePath(), e.getMessage());
            }
        }

        return mapper().deleteById(id) > 0;
    }

    public void updateIndexResult(Long id, int chunkCount, String status, String error) {
        ReferenceDocumentDO row = mapper().selectById(id);
        if (row == null) return;
        row.setChunkCount(chunkCount);
        row.setIndexStatus(status);
        row.setIndexError(error);
        mapper().updateById(row);
    }

    public List<String> chunksForDocument(ReferenceDocumentDO meta) {
        if (meta == null || meta.getExtractedText() == null) {
            return Collections.emptyList();
        }
        return ReferenceDocumentChunker.chunk(meta.getExtractedText());
    }

    private ReferenceDocumentDO findByHash(Long userId, String hash) {
        LambdaQueryWrapper<ReferenceDocumentDO> q = new LambdaQueryWrapper<>();
        q.eq(ReferenceDocumentDO::getUserId, userId)
                .eq(ReferenceDocumentDO::getFileHash, hash)
                .ne(ReferenceDocumentDO::getDeleted, "y")
                .last("LIMIT 1");
        return mapper().selectOne(q);
    }

    private static Path storeOnDisk(Long id, String filename, byte[] content) throws IOException {
        Path dir = Path.of(DOCUMENTS_DIR);
        Files.createDirectories(dir);
        String safe = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path target = dir.resolve(id + "_" + safe);
        Files.write(target, content);
        return target;
    }

    public static String vectorId(Long documentId, int chunkIndex) {
        return "refdoc_" + documentId + "_" + chunkIndex;
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) return "untitled";
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 0x20 && c != 0x7F) sb.append(c);
        }
        String cleaned = sb.toString().strip();
        if (cleaned.isEmpty()) cleaned = "untitled";
        if (cleaned.getBytes(StandardCharsets.UTF_8).length > 480) {
            cleaned = cleaned.substring(0, Math.min(cleaned.length(), 120));
        }
        return cleaned;
    }

    private static String kindOf(String mime, String filename) {
        if (mime == null) return null;
        String m = mime.toLowerCase(Locale.ROOT);
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);

        if (m.equals("application/pdf")) return "pdf";
        if (m.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                || m.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation")
                || m.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) {
            return "office";
        }
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx") || lower.endsWith(".pptx") || lower.endsWith(".xlsx")) return "office";
        if (m.startsWith("text/")) return "text";
        if (m.equals("application/json") || m.equals("application/xml")
                || m.equals("application/x-yaml") || m.equals("application/sql")) {
            return "text";
        }
        List<String> textExts = List.of(".txt", ".md", ".markdown", ".log", ".csv", ".tsv",
                ".json", ".sql", ".yaml", ".yml", ".xml");
        for (String ext : textExts) {
            if (lower.endsWith(ext)) return "text";
        }
        return null;
    }

    private static String resolveMime(String filename, String declaredMime, byte[] bytes) {
        if (bytes != null && bytes.length >= 4
                && bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46) {
            return "application/pdf";
        }
        if (declaredMime != null && !declaredMime.isBlank()
                && !"application/octet-stream".equalsIgnoreCase(declaredMime)) {
            return declaredMime.toLowerCase(Locale.ROOT);
        }
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt") || lower.endsWith(".log")) return "text/plain";
        return declaredMime != null ? declaredMime : "application/octet-stream";
    }
}
