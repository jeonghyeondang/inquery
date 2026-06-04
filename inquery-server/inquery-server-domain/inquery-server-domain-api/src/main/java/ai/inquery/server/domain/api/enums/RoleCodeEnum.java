package ai.inquery.server.domain.api.enums;

import ai.inquery.server.tools.base.enums.BaseEnum;
import lombok.Getter;

/**
 * role code
 *
 */
@Getter
public enum RoleCodeEnum implements BaseEnum<String> {
    /**
     * DESKTOP
     */
    DESKTOP("DESKTOP", 1L, "_desktop_default_user_name", "_desktop_default_user_name"),

    /**
     * ADMIN
     */
    ADMIN("ADMIN", 2L, System.getenv().getOrDefault("ADMIN_NAME","admin123"),
            System.getenv().getOrDefault("ADMIN_PASSWORD","admin1234")),

    /**
     * USER
     */
    USER("USER", null, null, null),

    ;
    final String description;
    final Long defaultUserId;
    final String userName;
    final String password;

    RoleCodeEnum(String description, Long defaultUserId, String userName, String password) {
        this.description = description;
        this.defaultUserId = defaultUserId;
        this.userName = userName;
        this.password = password;
    }

    @Override
    public String getCode() {
        return this.name();
    }
}
