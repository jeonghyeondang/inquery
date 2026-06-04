package ai.inquery.server.tools.base.enums;

import lombok.Getter;

/**
 * Whether to enumerate
 *
 */
@Getter
public enum YesOrNoEnum implements BaseEnum<String> {

    /**
     * yes
     */
    YES("Y", "Yes", true),
    /**
     * no
     */
    NO("N", "No", false),

    ;

    final String letter;
    final String description;
    final boolean booleanValue;

    YesOrNoEnum(String letter, String description, boolean booleanValue) {
        this.letter = letter;
        this.description = description;
        this.booleanValue = booleanValue;
    }

    @Override
    public String getCode() {
        return this.name();
    }

    /**
     * Convert based on boolean value
     *
     * @param booleanValue Boolean value
     * @return
     */
    public static YesOrNoEnum valueOf(Boolean booleanValue) {
        if (booleanValue == null) {
            return null;
        }
        if (booleanValue) {
            return YesOrNoEnum.YES;
        }
        return YesOrNoEnum.NO;
    }

}
