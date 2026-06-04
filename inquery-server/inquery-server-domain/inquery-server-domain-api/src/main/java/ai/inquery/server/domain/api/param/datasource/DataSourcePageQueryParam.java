package ai.inquery.server.domain.api.param.datasource;

import ai.inquery.server.tools.base.wrapper.param.OrderBy;
import ai.inquery.server.tools.base.wrapper.param.PageQueryParam;
import lombok.Data;
import lombok.Getter;

/**
 * @version DataSourcePageQueryParam.java, v 0.1 September 23, 2022 15:27 moji Exp $
 */
@Data
public class DataSourcePageQueryParam extends PageQueryParam {

    /**
     * search keyword
     */
    private String searchKey;

    /**
     * Connection Type
     *
     * @see ai.inquery.server.domain.api.enums.DataSourceKindEnum
     */
    private String kind;

    @Getter
    public enum OrderCondition implements ai.inquery.server.tools.base.wrapper.param.OrderCondition {
        ID_DESC(OrderBy.desc("id")),
        ;

        final OrderBy orderBy;

        OrderCondition(OrderBy orderBy) {
            this.orderBy = orderBy;
        }
    }
}
