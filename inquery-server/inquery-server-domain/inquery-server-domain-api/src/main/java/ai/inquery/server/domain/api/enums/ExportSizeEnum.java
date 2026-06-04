package ai.inquery.server.domain.api.enums;

import ai.inquery.server.tools.base.enums.BaseEnum;
import lombok.Getter;

/**
 * How much data is currently needed at the beginning
 *
 */
@Getter
public enum ExportSizeEnum implements BaseEnum<String> {
    /**
     * CURRENT_PAGE
     */
    CURRENT_PAGE("CURRENT_PAGE"),

    /**
     * ALL
     */
    ALL("ALL"),

    ;

    final String description;

    ExportSizeEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }

}
