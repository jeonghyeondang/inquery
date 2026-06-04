package ai.inquery.server.web.api.controller.data.source.converter;

import java.util.List;

import ai.inquery.server.domain.api.enums.DataSourceKindEnum;
import ai.inquery.server.domain.api.model.DataSource;
import ai.inquery.server.domain.api.param.ConsoleCloseParam;
import ai.inquery.server.domain.api.param.ConsoleConnectParam;
import ai.inquery.server.domain.api.param.datasource.DataSourceCreateParam;
import ai.inquery.server.domain.api.param.datasource.DataSourcePageQueryParam;
import ai.inquery.server.domain.api.param.datasource.DataSourcePreConnectParam;
import ai.inquery.server.domain.api.param.datasource.DataSourceUpdateParam;
import ai.inquery.server.web.api.controller.data.source.request.ConsoleCloseRequest;
import ai.inquery.server.web.api.controller.data.source.request.ConsoleConnectRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceCreateRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceQueryRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceTestRequest;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceUpdateRequest;
import ai.inquery.server.web.api.controller.data.source.vo.DataSourceVO;
import ai.inquery.server.web.api.controller.data.source.vo.DatabaseVO;
import ai.inquery.spi.model.Database;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * @version DataSourceWebConverter.java, v 0.1 September 23, 2022 16:45 moji Exp $
 */
@Mapper(componentModel = "spring", imports = {DataSourceKindEnum.class}, builder = @Builder(disableBuilder = true))
public abstract class DataSourceWebConverter {

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(source = "user", target = "userName"),
        @Mapping(target = "kind", expression = "java(DataSourceKindEnum.SHARED.getCode())"),
        @Mapping(target = "envType", ignore = true),
    })
    public abstract DataSourceCreateParam createReq2param(DataSourceCreateRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(source = "user", target = "userName")
    })
    public abstract DataSourceUpdateParam updateReq2param(DataSourceUpdateRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "enableReturnCount", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
    })
    public abstract DataSourcePageQueryParam queryReq2param(DataSourceQueryRequest request);

    /**
     * Model conversion
     *
     * @param dataSource
     * @return
     */
    @Mappings({
        @Mapping(target = "user", source = "userName"),
        @Mapping(target = "authenticationType", ignore = true),
    })
    public abstract DataSourceVO dto2vo(DataSource dataSource);

    /**
     * Model conversion
     *
     * @param dataSources
     * @return
     */
    public abstract List<DataSourceVO> dto2vo(List<DataSource> dataSources);

    /**
     * Model conversion
     *
     * @param databaseDTO
     * @return
     */
    @Mappings({
        @Mapping(target = "description", ignore = true),
        @Mapping(target = "count", ignore = true),
    })
    public abstract DatabaseVO databaseDto2vo(Database databaseDTO);

    /**
     * Model conversion
     *
     * @param databaseDTOS
     * @return
     */
    public abstract List<DatabaseVO> databaseDto2vo(List<Database> databaseDTOS);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract DataSourcePreConnectParam testRequest2param(DataSourceTestRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract ConsoleConnectParam request2connectParam(ConsoleConnectRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract ConsoleCloseParam request2closeParam(ConsoleCloseRequest request);
}
