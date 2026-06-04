package ai.inquery.server.web.api.controller.ai.attachment;

import ai.inquery.server.domain.repository.entity.AiChatAttachmentDO;
import lombok.Data;

import java.util.Date;

/**
 * Lightweight projection of {@link AiChatAttachmentDO} returned to the
 * frontend. Excludes the heavy columns ({@code content},
 * {@code extracted_text}, {@code thumbnail_content}) — those have
 * dedicated endpoints.
 */
@Data
public class AttachmentMetaDTO {
    private Long id;
    private Long chatRoomId;
    private String filename;
    private String mimeType;
    private Long sizeBytes;
    private String kind;
    private boolean hasThumbnail;
    private boolean hasExtractedText;
    private Date gmtCreate;

    public static AttachmentMetaDTO from(AiChatAttachmentDO row) {
        AttachmentMetaDTO dto = new AttachmentMetaDTO();
        dto.setId(row.getId());
        dto.setChatRoomId(row.getChatRoomId());
        dto.setFilename(row.getFilename());
        dto.setMimeType(row.getMimeType());
        dto.setSizeBytes(row.getSizeBytes());
        dto.setKind(row.getKind());
        dto.setHasThumbnail(row.getThumbnailMime() != null);
        // We don't have extracted_text loaded here (lazy column), so
        // approximate from kind: image never has it, pdf/text always do
        // (modulo extraction failures, which the chat path tolerates).
        dto.setHasExtractedText(!"image".equals(row.getKind()));
        dto.setGmtCreate(row.getGmtCreate());
        return dto;
    }
}
