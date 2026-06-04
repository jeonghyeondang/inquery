package ai.inquery.server.common.api.controller.converter;

import java.util.List;

import ai.inquery.server.common.api.controller.vo.SimpleEnvironmentVO;
import ai.inquery.server.domain.api.model.Environment;

/**
 * converter
 *
 */
public interface EnvironmentCommonConverter {

    /**
     * convert
     *
     * @param list
     * @return
     */
    List<SimpleEnvironmentVO> dto2vo(List<Environment> list);
}
