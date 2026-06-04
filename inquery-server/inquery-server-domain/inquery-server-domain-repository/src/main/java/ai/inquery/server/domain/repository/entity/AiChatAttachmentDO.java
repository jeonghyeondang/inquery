package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;

/**
 * AI Chat attachment row.
 *
 * <p>Large columns ({@code content}, {@code extracted_text},
 * {@code thumbnail_content}) are excluded from default SELECT to keep
 * list / meta queries cheap. Use the dedicated mapper methods on
 * {@code AiChatAttachmentMapper} (selectContentById /
 * selectThumbnailById / selectExtractedTextById) when you actually
 * need the bytes.
 */
@Getter
@Setter
@TableName("ai_chat_attachment")
public class AiChatAttachmentDO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Date gmtCreate;
    private Date gmtModified;

    private Long userId;
    private Long chatRoomId;

    private String filename;
    private String mimeType;
    private Long sizeBytes;

    /** "image" | "pdf" | "text" */
    private String kind;

    /** Lazy: fetch with selectContentById when needed. */
    @TableField(select = false)
    private byte[] content;

    /** Lazy: fetch with selectExtractedTextById when needed. */
    @TableField(select = false)
    private String extractedText;

    /** Lazy: fetch with selectThumbnailById when needed. */
    @TableField(select = false)
    private byte[] thumbnailContent;

    private String thumbnailMime;

    private String deleted;
}
