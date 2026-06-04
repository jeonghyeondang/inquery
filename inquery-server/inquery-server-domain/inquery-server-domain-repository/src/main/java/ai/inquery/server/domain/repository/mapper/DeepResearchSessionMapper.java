package ai.inquery.server.domain.repository.mapper;

import ai.inquery.server.domain.repository.entity.DeepResearchSessionDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Deep Research Session Mapper interface.
 */
@Mapper
public interface DeepResearchSessionMapper extends BaseMapper<DeepResearchSessionDO> {

    /**
     * Find active research session by chat room ID.
     * Active means status is not COMPLETED or FAILED.
     */
    @Select("SELECT * FROM deep_research_session WHERE chat_room_id = #{chatRoomId} AND status IN ('PLANNING', 'RUNNING') ORDER BY gmt_create DESC LIMIT 1")
    DeepResearchSessionDO findActiveByRoomId(@Param("chatRoomId") Long chatRoomId);

    /**
     * Find latest completed research session by chat room ID.
     */
    @Select("SELECT * FROM deep_research_session WHERE chat_room_id = #{chatRoomId} AND status = 'COMPLETED' ORDER BY gmt_create DESC LIMIT 1")
    DeepResearchSessionDO findLatestCompletedByRoomId(@Param("chatRoomId") Long chatRoomId);

    /**
     * Find all sessions for a chat room.
     */
    @Select("SELECT * FROM deep_research_session WHERE chat_room_id = #{chatRoomId} ORDER BY gmt_create DESC")
    List<DeepResearchSessionDO> findByRoomId(@Param("chatRoomId") Long chatRoomId);

    /**
     * Update session status.
     */
    @Update("UPDATE deep_research_session SET status = #{status}, gmt_modified = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * Delete old completed sessions (cleanup).
     * Keeps only the most recent completed session per chat room.
     */
    @Update("DELETE FROM deep_research_session WHERE status = 'COMPLETED' AND gmt_create < DATE_SUB(NOW(), INTERVAL 7 DAY)")
    int cleanupOldSessions();
}
