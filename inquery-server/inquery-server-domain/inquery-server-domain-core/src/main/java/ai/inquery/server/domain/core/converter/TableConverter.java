package ai.inquery.server.domain.core.converter;

import ai.inquery.server.domain.api.param.TableVectorParam;
import ai.inquery.server.domain.repository.entity.TableVectorMappingDO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public abstract class TableConverter {

    /**
     * TableVectorParam to TableVectorMappingDO
     *
     * @param param
     * @return
     */
    @Mapping(target = "id", ignore = true)
    public abstract TableVectorMappingDO toTableVectorMappingDO(TableVectorParam param);
}
