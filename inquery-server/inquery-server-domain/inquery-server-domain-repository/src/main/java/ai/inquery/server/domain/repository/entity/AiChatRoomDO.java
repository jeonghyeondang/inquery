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
 * AI Chat Room
 * </p>
 *
 * @since 2025-10-27
 */
@Getter
@Setter
@TableName("ai_chat_room")
public class AiChatRoomDO implements Serializable {

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
     * Conversation ID (UUID for backend context)
     */
    private String conversationId;

    /**
     * Chat room title
     */
    private String title;

    /**
     * User ID
     */
    private Long userId;

    /**
     * Whether it has been deleted, y means deleted, n means not deleted
     */
    private String deleted;
}



