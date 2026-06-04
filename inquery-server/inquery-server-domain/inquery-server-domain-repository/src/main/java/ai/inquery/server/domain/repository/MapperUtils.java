package ai.inquery.server.domain.repository;

import ai.inquery.server.domain.repository.mapper.AiChatMessageMapper;
import ai.inquery.server.domain.repository.mapper.AiChatRoomMapper;
import ai.inquery.server.domain.repository.mapper.AiFeedbackMapper;
import ai.inquery.server.domain.repository.mapper.TaskMapper;

public class MapperUtils {

    public static TaskMapper getTaskMapper() {
        return Dbutils.getMapper(TaskMapper.class);
    }

    public static AiChatRoomMapper getAiChatRoomMapper() {
        return Dbutils.getMapper(AiChatRoomMapper.class);
    }

    public static AiChatMessageMapper getAiChatMessageMapper() {
        return Dbutils.getMapper(AiChatMessageMapper.class);
    }

    public static AiFeedbackMapper getAiFeedbackMapper() {
        return Dbutils.getMapper(AiFeedbackMapper.class);
    }
}
