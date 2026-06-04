package ai.inquery.server.tools.base.enums;

import lombok.Getter;

/**
 * System environment
 *
 */
@Getter
public enum SystemEnvironmentEnum implements BaseEnum<String> {

    /**
     * dev
     */
    DEV("dev", "Local"),

    /**
     * test
     */
    TEST("test", "Test"),

    /**
     * release
     */
    RELEASE("release", "Production"),

    ;

    final String code;

    final String description;

    SystemEnvironmentEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }
}
