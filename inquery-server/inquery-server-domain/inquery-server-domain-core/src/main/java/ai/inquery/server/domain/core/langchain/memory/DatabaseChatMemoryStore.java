package ai.inquery.server.domain.core.langchain.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Database-backed ChatMemoryStore for persistent conversation history.
 * Stores chat messages in a database table for long-term persistence.
 */
@Slf4j
public class DatabaseChatMemoryStore implements ChatMemoryStore {

    private final DataSource dataSource;
    private final String tableName;

    public DatabaseChatMemoryStore(DataSource dataSource) {
        this(dataSource, "ai_chat_memory");
    }

    public DatabaseChatMemoryStore(DataSource dataSource, String tableName) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        initializeTable();
    }

    /**
     * Create the table if it doesn't exist.
     */
    private void initializeTable() {
        String createTableSql = """
            CREATE TABLE IF NOT EXISTS %s (
                id BIGSERIAL PRIMARY KEY,
                memory_id VARCHAR(255) NOT NULL,
                message_type VARCHAR(50) NOT NULL,
                content TEXT NOT NULL,
                metadata TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """.formatted(tableName);

        String createIndexMemoryId = "CREATE INDEX IF NOT EXISTS idx_%s_memory_id ON %s (memory_id)".formatted(tableName, tableName);
        String createIndexCreatedAt = "CREATE INDEX IF NOT EXISTS idx_%s_created_at ON %s (created_at)".formatted(tableName, tableName);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSql);
            stmt.execute(createIndexMemoryId);
            stmt.execute(createIndexCreatedAt);
            log.info("Chat memory table initialized: {}", tableName);
        } catch (SQLException e) {
            log.warn("Failed to create chat memory table (may already exist): {}", e.getMessage());
        }
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String id = memoryId.toString();
        List<ChatMessage> messages = new ArrayList<>();

        String selectSql = "SELECT message_type, content, metadata FROM " + tableName +
                " WHERE memory_id = ? ORDER BY created_at ASC";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(selectSql)) {

            stmt.setString(1, id);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String messageType = rs.getString("message_type");
                String content = rs.getString("content");

                ChatMessage message = deserializeMessage(messageType, content);
                if (message != null) {
                    messages.add(message);
                }
            }

            log.debug("Loaded {} messages for memory_id: {}", messages.size(), id);
        } catch (SQLException e) {
            log.error("Failed to load messages for memory_id: {}", id, e);
        }

        return messages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = memoryId.toString();

        // Delete existing messages and insert new ones (transactional)
        String deleteSql = "DELETE FROM " + tableName + " WHERE memory_id = ?";
        String insertSql = "INSERT INTO " + tableName + " (memory_id, message_type, content, metadata) VALUES (?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // Delete old messages
                try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                    deleteStmt.setString(1, id);
                    deleteStmt.executeUpdate();
                }

                // Insert new messages
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    for (ChatMessage message : messages) {
                        insertStmt.setString(1, id);
                        insertStmt.setString(2, getMessageType(message));
                        insertStmt.setString(3, serializeContent(message));
                        insertStmt.setString(4, null); // metadata can be added later
                        insertStmt.addBatch();
                    }
                    insertStmt.executeBatch();
                }

                conn.commit();
                log.debug("Saved {} messages for memory_id: {}", messages.size(), id);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to update messages for memory_id: {}", id, e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String id = memoryId.toString();
        String deleteSql = "DELETE FROM " + tableName + " WHERE memory_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(deleteSql)) {

            stmt.setString(1, id);
            int deleted = stmt.executeUpdate();
            log.info("Deleted {} messages for memory_id: {}", deleted, id);
        } catch (SQLException e) {
            log.error("Failed to delete messages for memory_id: {}", id, e);
        }
    }

    /**
     * Get message count for a memory ID (useful for diagnostics).
     */
    public int getMessageCount(String memoryId) {
        String countSql = "SELECT COUNT(*) FROM " + tableName + " WHERE memory_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(countSql)) {

            stmt.setString(1, memoryId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("Failed to count messages for memory_id: {}", memoryId, e);
        }

        return 0;
    }

    /**
     * Clean up old messages (older than specified days).
     */
    public int cleanupOldMessages(int daysOld) {
        String deleteSql = "DELETE FROM " + tableName + " WHERE created_at < CURRENT_TIMESTAMP - make_interval(days => ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(deleteSql)) {

            stmt.setInt(1, daysOld);
            int deleted = stmt.executeUpdate();
            log.info("Cleaned up {} old messages (older than {} days)", deleted, daysOld);
            return deleted;
        } catch (SQLException e) {
            log.error("Failed to cleanup old messages", e);
            return 0;
        }
    }

    private String getMessageType(ChatMessage message) {
        if (message instanceof UserMessage) {
            return "USER";
        } else if (message instanceof AiMessage) {
            return "AI";
        } else if (message instanceof SystemMessage) {
            return "SYSTEM";
        }
        return "UNKNOWN";
    }

    private String serializeContent(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return userMessage.singleText();
        } else if (message instanceof AiMessage aiMessage) {
            return aiMessage.text();
        } else if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text();
        }
        return message.toString();
    }

    private ChatMessage deserializeMessage(String type, String content) {
        return switch (type) {
            case "USER" -> UserMessage.from(content);
            case "AI" -> AiMessage.from(content);
            case "SYSTEM" -> SystemMessage.from(content);
            default -> {
                log.warn("Unknown message type: {}", type);
                yield null;
            }
        };
    }
}
