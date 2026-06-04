package ai.inquery.server.common.api.controller.converter;

import ai.inquery.server.common.api.controller.vo.SimpleEnvironmentVO;
import ai.inquery.server.domain.api.model.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Manual converter implementation (avoid MapStruct-generated classpath issues)
 */
@Component
public class EnvironmentCommonConverterImpl implements EnvironmentCommonConverter {

    @Override
    public List<SimpleEnvironmentVO> dto2vo(List<Environment> list) {
        if (list == null) {
            return null;
        }
        List<SimpleEnvironmentVO> result = new ArrayList<>(list.size());
        for (Environment env : list) {
            if (env == null) {
                result.add(null);
                continue;
            }
            SimpleEnvironmentVO vo = new SimpleEnvironmentVO();
            vo.setId(env.getId());
            vo.setName(env.getName());
            vo.setShortName(env.getShortName());
            vo.setColor(env.getColor());
            result.add(vo);
        }
        return result;
    }
}





