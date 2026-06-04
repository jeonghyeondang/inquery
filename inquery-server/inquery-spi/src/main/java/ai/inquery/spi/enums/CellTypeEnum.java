package ai.inquery.spi.enums;

import ai.inquery.server.tools.base.enums.BaseEnum;
import lombok.Getter;

/**
 * Driver class enumeration
 *
 */
@Getter
public enum CellTypeEnum implements BaseEnum<String> {
    /**
     * string
     */
    STRING("string"),

    /**
     * number
     */
    BIG_DECIMAL("number"),

    /**
     * date
     */
    DATE("date"),

    /**
     * binary stream
     */
    BYTE("binary stream"),

    /**
     * empty data
     */
    EMPTY("empty data"),
    ;

    final String description;

    CellTypeEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }
}
