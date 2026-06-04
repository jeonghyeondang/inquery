package ai.inquery.server.tools.base.wrapper.param;

import java.io.Serializable;

import ai.inquery.server.tools.base.enums.OrderByDirectionEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * sorted objects
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderBy implements Serializable {
    /**
     * sort field
     */
    private String orderConditionName;
    /**
     * Sorting direction
     */
    private OrderByDirectionEnum direction;

    public static OrderBy of(String property, OrderByDirectionEnum direction) {
        return new OrderBy(property, direction);
    }

    public static OrderBy asc(String property) {
        return new OrderBy(property, OrderByDirectionEnum.ASC);
    }

    public static OrderBy desc(String property) {
        return new OrderBy(property, OrderByDirectionEnum.DESC);
    }
}