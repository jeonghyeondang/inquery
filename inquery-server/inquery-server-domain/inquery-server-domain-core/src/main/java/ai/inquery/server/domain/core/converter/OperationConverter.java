package ai.inquery.server.domain.core.converter;

import java.util.List;

import ai.inquery.server.domain.api.model.Operation;
import ai.inquery.server.domain.api.param.operation.OperationSavedParam;
import ai.inquery.server.domain.api.param.operation.OperationUpdateParam;
import ai.inquery.server.domain.repository.entity.OperationSavedDO;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * @version UserSavedDdlCoreConverter.java, v 0.1 September 25, 2022 15:50 moji Exp $
 */
@Mapper(componentModel = "spring")
public abstract class OperationConverter {

    /**
     * Parameter conversion
     *
     * @param param
     * @return
     */
    @Mappings({
        @Mapping(source = "schemaName", target = "dbSchemaName"),
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
        @Mapping(target = "userId", ignore = true),
    })
    public abstract OperationSavedDO param2do(OperationSavedParam param);

    /**
     * Parameter conversion
     *
     * @param param
     * @return
     */
    @Mappings({
        @Mapping(source = "schemaName", target = "dbSchemaName"),
        @Mapping(target = "gmtCreate", ignore = true),
        @Mapping(target = "gmtModified", ignore = true),
        @Mapping(target = "userId", ignore = true),
    })
    public abstract OperationSavedDO param2do(OperationUpdateParam param);

    /**
     * Model conversion
     *
     * @param userSavedDdlDO
     * @return
     */
    @Mappings({
        @Mapping(source = "dbSchemaName", target = "schemaName"),
        @Mapping(target = "dataSourceName", ignore = true),
    })
    public abstract Operation do2dto(OperationSavedDO userSavedDdlDO);

    /**
     * Model conversion
     *
     * @param userSavedDdlDOS
     * @return
     */
    public abstract List<Operation> do2dto(List<OperationSavedDO> userSavedDdlDOS);
}
