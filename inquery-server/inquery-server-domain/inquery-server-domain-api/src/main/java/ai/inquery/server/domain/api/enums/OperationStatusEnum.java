package ai.inquery.server.domain.api.enums;

import ai.inquery.server.tools.base.enums.BaseEnum;

import lombok.Getter;

/**
 * state
 *
 */
@Getter
public enum OperationStatusEnum implements BaseEnum<String> {
    /**
     * draft
     */
    DRAFT("Draft"),

    /**
     * Published
     */
    RELEASE("Published"),

    ;

    final String description;

    OperationStatusEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }
}
