package ai.inquery.server.domain.core.converter;

import java.util.List;

import ai.inquery.server.domain.api.model.DataSourceAccess;
import ai.inquery.server.domain.api.param.datasource.access.DataSourceAccessCreatParam;
import ai.inquery.server.domain.repository.entity.DataSourceAccessDO;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * converter
 *
 */
@Slf4j
@Mapper(componentModel = "spring")
public abstract class DataSourceAccessConverter {

    /**
     * convert
     *
     * @param data
     * @return
     */
    @Mappings({
        @Mapping(target = "accessObject.id", source = "accessObjectId"),
        @Mapping(target = "accessObject.type", source = "accessObjectType"),
        @Mapping(target = "dataSource.id", source = "dataSourceId"),
    })
    public abstract DataSourceAccess do2dto(DataSourceAccessDO data);

    /**
     * convert
     *
     * @param dataSourceId
     * @param accessObjectId
     * @param accessObjectType
     * @param userId
     * @return
     */
    @Mappings({
        @Mapping(target = "createUserId", source = "userId"),
        @Mapping(target = "modifiedUserId", source = "userId"),
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
    })
    public abstract DataSourceAccessDO param2do(Long dataSourceId, Long accessObjectId, String accessObjectType,
        Long userId);

    /**
     * convert
     *
     * @param param

     * @return
     */
    @Mappings({
        @Mapping(target = "createUserId", source = "userId"),
        @Mapping(target = "modifiedUserId", source = "userId"),
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
    })
    public abstract DataSourceAccessDO param2do(DataSourceAccessCreatParam param, Long userId);


    /**
     * convert
     *
     * @param list
     * @return
     */
    public abstract List<DataSourceAccess> do2dto(List<DataSourceAccessDO> list);
}
