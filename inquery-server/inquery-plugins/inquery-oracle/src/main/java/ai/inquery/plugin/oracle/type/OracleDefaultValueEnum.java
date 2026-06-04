package ai.inquery.plugin.oracle.type;

import ai.inquery.spi.model.DefaultValue;

import java.util.Arrays;
import java.util.List;

public enum OracleDefaultValueEnum {

    EMPTY_STRING("EMPTY_STRING"),
    NULL("NULL"),
    ;
    private DefaultValue defaultValue;

    OracleDefaultValueEnum(String defaultValue) {
        this.defaultValue = new DefaultValue(defaultValue);
    }


    public DefaultValue getDefaultValue() {
        return defaultValue;
    }

    public static List<DefaultValue> getDefaultValues() {
        return Arrays.stream(OracleDefaultValueEnum.values()).map(OracleDefaultValueEnum::getDefaultValue).collect(java.util.stream.Collectors.toList());
    }

}
