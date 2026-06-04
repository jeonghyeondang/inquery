package ai.inquery.server.domain.api.enums;

import ai.inquery.server.tools.base.enums.BaseEnum;
import lombok.Getter;

/**
 * Is it a valid enumeration
 *
 */
@Getter
public enum ValidStatusEnum implements BaseEnum<String> {
    /**
     * VALID
     */
    VALID("VALID"),

    /**
     * INVALID
     */
    INVALID("INVALID"),

    ;
    final String description;

    ValidStatusEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }
}
