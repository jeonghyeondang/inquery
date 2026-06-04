package ai.inquery.server.tools.base.enums;

/**
 * Enumeration of sorting directions
 *
 */
public enum OrderByDirectionEnum implements BaseEnum<String> {

    /**
     * asc
     */
    ASC,
    /**
     * desc
     */
    DESC;

    @Override
    public String getCode() {
        return this.name();
    }

    @Override
    public String getDescription() {
        return this.name();
    }
}
