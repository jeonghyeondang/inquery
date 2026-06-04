package ai.inquery.server.domain.api.enums;

import ai.inquery.server.tools.base.enums.BaseEnum;
import lombok.Getter;

import javax.swing.text.html.HTML;

/**
 * export type
 *
 */
@Getter
public enum ExportTypeEnum implements BaseEnum<String> {
    /**
     * CSV
     */
    CSV("CSV"),

    /**
     * INSERT
     */
    INSERT("INSERT"),

    /**
     * WORD
     */
    WORD("WORD"),

    /**
     * EXCEL
     */
    EXCEL("EXCEL"),

    /**
     * HTML
     */
    HTML("HTML"),

    /**
     * MARKDOWN
     */
    MARKDOWN("MARKDOWN"),

    /**
     * PDF
     */
    PDF("PDF"),

    JSON("JSON"),

    SQL("SQL");

    final String description;

    ExportTypeEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }

}
