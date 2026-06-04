package ai.inquery.server.domain.api.enums;

import ai.inquery.server.tools.base.enums.BaseEnum;
import lombok.Getter;

/**
 * Access Object Type
 *
 */
@Getter
public enum AccessObjectTypeEnum implements BaseEnum<String> {
    /**
     * TEAM
     */
    TEAM("TEAM"),

    /**
     * USER
     */
    USER("USER"),

    ;

    final String description;

    AccessObjectTypeEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }

}
