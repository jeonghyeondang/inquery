package ai.inquery.plugin.sqlite.type;

import ai.inquery.spi.model.DefaultValue;

import java.util.Arrays;
import java.util.List;

public enum SqliteDefaultValueEnum {
    EMPTY_STRING("EMPTY_STRING"),
    NULL("NULL"),
    ;
    private DefaultValue defaultValue;

    SqliteDefaultValueEnum(String defaultValue) {
        this.defaultValue = new DefaultValue(defaultValue);
    }


    public DefaultValue getDefaultValue() {
        return defaultValue;
    }

    public static List<DefaultValue> getDefaultValues() {
        return Arrays.stream(SqliteDefaultValueEnum.values()).map(SqliteDefaultValueEnum::getDefaultValue).collect(java.util.stream.Collectors.toList());
    }

}
