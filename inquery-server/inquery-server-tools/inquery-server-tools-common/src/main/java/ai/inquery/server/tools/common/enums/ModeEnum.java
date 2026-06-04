package ai.inquery.server.tools.common.enums;

import ai.inquery.server.tools.base.enums.BaseEnum;
import lombok.Getter;

/**
 * model
 *
 */
@Getter
public enum ModeEnum implements BaseEnum<String> {
    /**
     * DESKTOP
     */
    DESKTOP("DESKTOP"),

    /**
     * WEB
     */
    WEB("WEB"),

    ;
    final String description;

    ModeEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }
}
