package ai.inquery.server.domain.repository.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * N:N mapping between {@code ai_chat_message} and
 * {@code ai_chat_attachment}.
 *
 * <p>Composite primary key (message_id, attachment_id) is not
 * BaseMapper-friendly, so we drop down to plain JDBC-style mapping. The
 * table is intentionally narrow — anything richer (filename, mime,
 * etc.) lives on the attachment row and is joined in at read time.
 */
public interface AiChatMessageAttachmentMapper {

    @Insert("INSERT INTO ai_chat_message_attachment (message_id, attachment_id, position) " +
            "VALUES (#{messageId}, #{attachmentId}, #{position}) " +
            "ON CONFLICT (message_id, attachment_id) DO NOTHING")
    void insert(@Param("messageId") Long messageId,
                @Param("attachmentId") Long attachmentId,
                @Param("position") int position);

    @Delete("DELETE FROM ai_chat_message_attachment WHERE message_id = #{messageId}")
    void deleteByMessageId(@Param("messageId") Long messageId);

    @Select("SELECT attachment_id FROM ai_chat_message_attachment " +
            "WHERE message_id = #{messageId} ORDER BY position ASC, attachment_id ASC")
    List<Long> findAttachmentIdsByMessageId(@Param("messageId") Long messageId);

    /**
     * Bulk-fetch mappings for a set of messages. Combined with one
     * follow-up SELECT on {@code ai_chat_attachment} this keeps the
     * "list messages in a room" path at 2 queries regardless of how
     * many messages or attachments the room has.
     */
    @Select({
            "<script>",
            "SELECT message_id, attachment_id, position FROM ai_chat_message_attachment",
            "WHERE message_id IN",
            "<foreach collection='messageIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "ORDER BY message_id ASC, position ASC, attachment_id ASC",
            "</script>"
    })
    @Results({
            @Result(column = "message_id",    property = "messageId"),
            @Result(column = "attachment_id", property = "attachmentId"),
            @Result(column = "position",      property = "position")
    })
    List<Mapping> findByMessageIds(@Param("messageIds") List<Long> messageIds);

    /** Row projection used by {@link #findByMessageIds}. */
    class Mapping {
        private Long messageId;
        private Long attachmentId;
        private Integer position;

        public Long getMessageId() { return messageId; }
        public void setMessageId(Long messageId) { this.messageId = messageId; }
        public Long getAttachmentId() { return attachmentId; }
        public void setAttachmentId(Long attachmentId) { this.attachmentId = attachmentId; }
        public Integer getPosition() { return position; }
        public void setPosition(Integer position) { this.position = position; }
    }
}
