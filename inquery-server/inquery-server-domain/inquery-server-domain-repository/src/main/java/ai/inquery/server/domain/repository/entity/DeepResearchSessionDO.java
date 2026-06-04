package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/**
 * Deep Research Session entity.
 * Stores temporary research data (MD content) during research execution.
 * Data should be deleted after research is complete.
 */
@Getter
@Setter
@TableName("deep_research_session")
public class DeepResearchSessionDO implements Serializable {

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
     * Associated chat room ID
     */
    private Long chatRoomId;

    /**
     * Original research question
     */
    private String question;

    /**
     * Research plan JSON (title, steps, estimated time)
     */
    private String researchPlan;

    /**
     * Accumulated research MD content
     */
    private String mdContent;

    /**
     * Final report JSON
     */
    private String reportJson;

    /**
     * Research status: PLANNING, RUNNING, COMPLETED, FAILED
     */
    private String status;

    /**
     * Current iteration number (1-3)
     */
    private Integer currentIteration;

    /**
     * Total queries executed
     */
    private Integer totalQueriesExecuted;

    /**
     * Error message if failed
     */
    private String errorMessage;

    /**
     * User ID who initiated the research
     */
    private Long userId;

    /**
     * Generated infographic HTML
     */
    private String infographicHtml;
}
