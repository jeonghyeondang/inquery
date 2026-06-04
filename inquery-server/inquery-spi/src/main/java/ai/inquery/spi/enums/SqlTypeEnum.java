package ai.inquery.spi.enums;

import ai.inquery.server.tools.base.enums.BaseEnum;
import lombok.Getter;

/**
 * sql type
 *
 */
@Getter
public enum SqlTypeEnum implements BaseEnum<String> {
    /**
     * Check for phrases
     */
    SELECT("Check for phrases"),

    /**
     * unknow
     */
    UNKNOWN("unknow"),

    ;

    final String description;

    SqlTypeEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }
}
