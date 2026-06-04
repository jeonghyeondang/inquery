package ai.inquery.server.tools.base.enums;

import lombok.Getter;

/**
 * @version WhiteListTypeEnum.java, v 0.1 September 25, 2022 16:57 moji Exp $
 */
@Getter
public enum WhiteListTypeEnum implements BaseEnum<String> {

    /**
     * vector interface
     */
    VECTOR("VECTOR"),

    ;

    final String description;

    WhiteListTypeEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }
}
