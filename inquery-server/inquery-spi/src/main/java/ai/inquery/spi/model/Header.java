package ai.inquery.spi.model;

import ai.inquery.spi.enums.DataTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;

/**
 * cell header
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Header implements Serializable {
    private static final long serialVersionUID = 1L;
    /**
     * cell type
     *
     * @see DataTypeEnum
     */
    private String dataType;

    /**
     * display name
     */
    private String name;


    private Boolean primaryKey;


    private String comment;

    private String defaultValue;

    private Integer autoIncrement;

    private Integer nullable;

    private Integer columnSize;

    private Integer decimalDigits;

}
