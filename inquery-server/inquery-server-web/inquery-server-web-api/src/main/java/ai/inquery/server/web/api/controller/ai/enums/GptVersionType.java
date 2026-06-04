package ai.inquery.server.web.api.controller.ai.enums;

import ai.inquery.server.tools.base.enums.BaseEnum;

import lombok.Getter;

/**
 * @version GptModelType.java, v 0.1 April 9, 2023 19:05 moji Exp $
 */
@Getter
public enum GptVersionType implements BaseEnum {

    /**
     * GPT-3
     */
    GPT3("GPT-3"),

    /**
     * GPT-3-5
     */
    GPT35("GPT-3.5"),
    ;

    final String description;

    GptVersionType(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }
}
