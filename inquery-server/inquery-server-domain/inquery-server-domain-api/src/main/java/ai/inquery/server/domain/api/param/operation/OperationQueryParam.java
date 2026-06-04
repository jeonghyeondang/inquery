package ai.inquery.server.domain.api.param.operation;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/**
 * query
 *
 */
@Data
@NoArgsConstructor
public class OperationQueryParam {

    /**
     * primary key
     */
    @NonNull
    private Long id;

    /**
     * user id
     */
    @NonNull
    private Long userId;
}
