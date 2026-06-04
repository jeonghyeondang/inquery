package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * Milvus mapping table saves records
 * </p>
 *
 * @since 2023-10-14
 */
@Getter
@Setter
@TableName("table_vector_mapping")
public class TableVectorMappingDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * primary key
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * api key
     */
    private String apiKey;

    /**
     * Data source connection ID
     */
    private Long dataSourceId;

    /**
     * Name database
     */
    private String database;

    /**
     * schema name
     */
    private String schema;

    /**
     * Vector saved state
     */
    private String status;
}
