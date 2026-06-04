package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * AI Chat Message
 * </p>
 *
 * @since 2025-10-27
 */
@Getter
@Setter
@TableName("ai_chat_message")
public class AiChatMessageDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Primary key
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Creation time
     */
    private Date gmtCreate;

    /**
     * Modified time
     */
    private Date gmtModified;

    /**
     * Chat room ID
     */
    private Long chatRoomId;

    /**
     * Role (user/assistant)
     */
    private String role;

    /**
     * Message content
     */
    private String content;

    /**
     * User ID
     */
    private Long userId;
}



