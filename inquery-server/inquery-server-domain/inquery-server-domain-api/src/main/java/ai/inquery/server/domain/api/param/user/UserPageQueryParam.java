package ai.inquery.server.domain.api.param.user;

import ai.inquery.server.tools.base.wrapper.param.OrderBy;
import ai.inquery.server.tools.base.wrapper.param.PageQueryParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * * page query
 *
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class UserPageQueryParam extends PageQueryParam {

    /**
     * searchKey
     */
    private String searchKey;

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
