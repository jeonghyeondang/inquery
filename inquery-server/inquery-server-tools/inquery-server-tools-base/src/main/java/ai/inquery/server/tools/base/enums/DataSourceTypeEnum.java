package ai.inquery.server.tools.base.enums;

import lombok.Getter;

/**
 * @version ConnectionTypeEnum.java, v 0.1 September 16, 2022 14:59 moji Exp $
 */
@Getter
public enum DataSourceTypeEnum implements BaseEnum<String> {

    /**
     * mysql database connection
     */
    MYSQL("mysql database connection"),

    /**
     * redis database connection
     */
    REDIS("redis database connection"),

    /**
     * sqlserver database connection
     */
    SQLSERVER("sqlserver database connection"),

    /**
     * mongo database connection
     */
    MONGODB("mongo database connection"),

    ;

    final String description;

    DataSourceTypeEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }
}
