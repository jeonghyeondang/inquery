package ai.inquery.server.domain.api.enums;


import ai.inquery.server.tools.base.enums.BaseEnum;

import lombok.Getter;

/**
 * AI model type selected by AI SQL
 *
 */
@Getter
public enum AiSqlSourceEnum implements BaseEnum<String> {
    /**
     * OPENAI
     */
    OPENAI( "OPENAI"),

    /**
     * CLAUDE AI
     */
    CLAUDEAI("CLAUDE AI"),

    /**
     * GOOGLE GEMINI
     */
    GEMINI("GOOGLE GEMINI"),

    ;

    final String description;


    AiSqlSourceEnum(String description) {
        this.description = description;
    }

    /**
     * Get enum by name
     *
     * @param name
     * @return
     */
    public static AiSqlSourceEnum getByName(String name) {
        for (AiSqlSourceEnum dbTypeEnum : AiSqlSourceEnum.values()) {
            if (dbTypeEnum.name().equals(name)) {
                return dbTypeEnum;
            }
        }
        return null;
    }

    @Override
    public String getCode() {
        return this.name();
    }

}
