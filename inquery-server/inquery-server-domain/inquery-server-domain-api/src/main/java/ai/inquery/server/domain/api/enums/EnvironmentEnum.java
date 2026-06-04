package ai.inquery.server.domain.api.enums;

import ai.inquery.server.tools.base.enums.BaseEnum;
import lombok.Getter;

/**
 * Environment
 *
 */
@Getter
public enum EnvironmentEnum implements BaseEnum<Long> {
    /**
     * RELEASE
     */
    RELEASE(1L, "RELEASE"),

    /**
     * TEST
     */
    TEST(2L, "TEST"),

    ;
    final Long code;
    final String description;

    EnvironmentEnum(Long code, String description) {
        this.code = code;
        this.description = description;
    }

}
