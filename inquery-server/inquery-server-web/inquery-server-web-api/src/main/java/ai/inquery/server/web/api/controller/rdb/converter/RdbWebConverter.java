package ai.inquery.server.web.api.controller.rdb.converter;

import java.util.List;

import ai.inquery.server.domain.api.param.*;
import ai.inquery.server.web.api.controller.data.source.vo.DatabaseVO;
import ai.inquery.server.web.api.controller.rdb.request.*;
import ai.inquery.server.web.api.controller.rdb.vo.*;
import ai.inquery.server.web.api.http.request.EsTableSchemaRequest;
import ai.inquery.spi.model.*;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

/**
 * @version MysqlDataConverter.java, v 0.1 October 14, 2022 14:04 moji Exp $
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public abstract class RdbWebConverter {

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mapping(target = "tableName", ignore = true)
    public abstract DlExecuteParam request2param(DmlRequest request);


    @Mapping(target = "consoleId", ignore = true)
    public abstract GroupByParam request2param(GroupByRequest request);
    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mapping(target = "consoleId", ignore = true)
    public abstract OrderByParam request2param(OrderByRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mapping(target = "sql", ignore = true)
    public abstract DlExecuteParam request2param(DmlTableRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mapping(target = "tableName", ignore = true)
    public abstract DlExecuteParam tableManageRequest2param(DdlRequest request);


    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract DlCountParam request2param(DdlCountRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
        @Mapping(target = "catalogTableName", ignore = true),
    })
    public abstract TableQueryParam tableRequest2param(TableDetailQueryRequest request);
    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
    })
    public abstract SequenceQueryParam sequenceRequest2param(SequenceDetailQueryRequest request);
    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "dbType", ignore = true),
        @Mapping(target = "type", ignore = true),
        @Mapping(target = "pinned", ignore = true),
        @Mapping(target = "ddl", ignore = true),
        @Mapping(target = "tablespace", ignore = true),
        @Mapping(target = "rows", ignore = true),
        @Mapping(target = "dataLength", ignore = true),
        @Mapping(target = "createTime", ignore = true),
        @Mapping(target = "updateTime", ignore = true),
    })
    public abstract Table tableRequest2param(TableRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    public abstract Sequence sequenceRequest2param(SequenceRequest request);

    /**
     * Parameter conversion
     *
     * @param dto
     * @return
     */
    public abstract SqlVO dto2vo(Sql dto);
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
        @Mapping(target = "tableName", ignore = true),
    })
    public abstract TablePageQueryParam tablePageRequest2param(TableBriefQueryRequest request);
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
        @Mapping(target = "tableName", ignore = true),
        @Mapping(target = "searchKey", ignore = true),
    })
    public abstract TablePageQueryParam tablePageRequest2param(DataExportRequest request);
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
        @Mapping(target = "sequenceName", ignore = true),
    })
    public abstract SequencePageQueryParam sequencePageRequest2param(SequenceBriefQueryRequest request);
    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
        @Mapping(target = "orderBy", ignore = true),
        @Mapping(target = "andOrderBy", ignore = true),
        @Mapping(target = "orderByList", ignore = true),
        @Mapping(target = "tableName", ignore = true),
        @Mapping(target = "catalogTableName", ignore = true),
    })
    public abstract TableQueryParam tableRequest2param(DataExportRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mapping(source = "name", target = "tableName")
    public abstract ShowCreateTableParam ddlExport2showTableCreate(DdlExportRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mapping(source = "name", target = "sequenceName")
    public abstract ShowCreateSequenceParam ddlExport2showSequenceCreate(DdlExportRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
            @Mapping(source = "tableName", target = "name"),
            @Mapping(source = "schemaName", target = "schema")
    })
    public abstract DropParam tableDelete2dropParam(TableDeleteRequest request);

    /**
     * Parameter conversion
     *
     * @param request
     * @return
     */
    @Mappings({
            @Mapping(source = "sequenceName", target = "name"),
            @Mapping(source = "schemaName", target = "schema")
    })
    public abstract DropParam sequenceDelete2dropParam(SequenceDeleteRequest request);


    /**
     * Model conversion
     *
     * @param dto
     * @return
     */
    public abstract ExecuteResultVO dto2vo(ExecuteResult dto);

    /**
     * Model conversion
     *
     * @param dtos
     * @return
     */
    public abstract List<ExecuteResultVO> dto2vo(List<ExecuteResult> dtos);

    /**
     * Model conversion
     *
     * @param dto
     * @return
     */
    @Mappings({
        @Mapping(target = "typeName", ignore = true),
        @Mapping(target = "nullableInt", ignore = true),
    })
    public abstract ColumnVO columnDto2vo(TableColumn dto);

    /**
     * Model conversion
     *
     * @param dtos
     * @return
     */
    public abstract List<ColumnVO> columnDto2vo(List<TableColumn> dtos);

    /**
     * Model conversion
     *
     * @param dto
     * @return
     */
    @Mappings({
        @Mapping(source = "columnList", target = "columnList"),
        @Mapping(target = "columns", ignore = true),
    })
    public abstract IndexVO indexDto2vo(TableIndex dto);

    /**
     * Model conversion
     *
     * @param dtos
     * @return
     */
    public abstract List<IndexVO> indexDto2vo(List<TableIndex> dtos);

    /**
     * Model conversion
     *
     * @param dto
     * @return
     */
    @Mappings({
        @Mapping(source = "columnList", target = "columnList"),
        @Mapping(source = "indexList", target = "indexList"),
    })
    public abstract TableVO tableDto2vo(Table dto);

    /**
     * Model conversion
     *
     * @param dto
     * @return
     */
    public abstract SequenceVO sequenceDto2vo(Sequence dto);

    /**
     * Model conversion
     *
     * @param dtos
     * @return
     */
    public abstract List<TableVO> tableDto2vo(List<Table> dtos);
    /**
     * Model conversion
     * @param tableColumns
     * @return
     */
    public abstract List<SchemaVO> schemaDto2vo(List<Schema> tableColumns);

    /**
     * Model conversion
     * @param dto
     * @return
     */
    public abstract SchemaVO schemaDto2vo(Schema dto);

    /**
     * Model conversion
     * @param dto
     * @return
     */
    @Mappings({
        @Mapping(target = "description", ignore = true),
        @Mapping(target = "count", ignore = true),
    })
    public abstract DatabaseVO databaseDto2vo(Database dto);


    /**
     * Model conversion
     * @param dto
     * @return
     */
    public abstract List<DatabaseVO> databaseDto2vo(List<Database> dto);

    public abstract MetaSchemaVO metaSchemaDto2vo(MetaSchema data);


    public abstract UpdateSelectResultParam request2param(SelectResultUpdateRequest request);

    @Mapping(target = "apikey", ignore = true)
    public abstract TableMilvusQueryRequest request2request(TableBriefQueryRequest request);

    @Mappings({
            @Mapping(source = "databaseName", target = "database"),
            @Mapping(source = "schemaName", target = "schema"),
            @Mapping(target = "apiKey", ignore = true),
            @Mapping(target = "status", ignore = true),
    })
    public abstract TableVectorParam param2param(TableBriefQueryRequest request);

    @Mappings({
            @Mapping(target = "apiKey", ignore = true),
            @Mapping(target = "tableName", ignore = true),
            @Mapping(target = "tableSchemaContent", ignore = true),
            @Mapping(target = "deleteBeforeInsert", ignore = true),
    })
    public abstract EsTableSchemaRequest req2req(TableBriefQueryRequest request);

    @Mappings({
            @Mapping(target = "orderBy", ignore = true),
            @Mapping(target = "andOrderBy", ignore = true),
            @Mapping(target = "pageNo", ignore = true),
            @Mapping(target = "pageSize", ignore = true),
            @Mapping(target = "enableReturnCount", ignore = true),
            @Mapping(target = "orderByList", ignore = true),
            @Mapping(target = "refresh", ignore = true),
    })
    public abstract TablePageQueryParam schemaReq2page(EsTableSchemaRequest request);

    @Mappings({
            @Mapping(target = "orderBy", ignore = true),
            @Mapping(target = "andOrderBy", ignore = true),
            @Mapping(target = "orderByList", ignore = true),
            @Mapping(target = "catalogTableName", ignore = true),
    })
    public abstract DmlSqlCopyParam dmlRequest2param(DmlSqlCopyRequest request) ;
}
