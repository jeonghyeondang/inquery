package ai.inquery.server.tools.base.enums;

import lombok.Getter;

/**
 * @version StatusEnum.java, v 0.1 September 25, 2022 16:57 moji Exp $
 */
@Getter
public enum StatusEnum implements BaseEnum<String> {

    /**
     * draft
     */
    DRAFT("draft"),

    /**
     * release
     */
    RELEASE("release"),

    ;

    final String description;

    StatusEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }
}
