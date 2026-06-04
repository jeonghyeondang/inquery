package ai.inquery.server.web.api.controller.rdb;

import ai.inquery.server.domain.api.param.*;
import ai.inquery.server.domain.api.param.datasource.DatabaseCreateParam;
import ai.inquery.server.domain.api.service.DatabaseService;
import ai.inquery.server.domain.api.service.DlTemplateService;
import ai.inquery.server.domain.api.service.TableService;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import ai.inquery.server.tools.base.wrapper.result.PageResult;
import ai.inquery.server.tools.base.wrapper.result.web.WebPageResult;
import ai.inquery.server.web.api.aspect.ConnectionInfoAspect;
import ai.inquery.server.web.api.controller.ai.EmbeddingController;
import ai.inquery.server.web.api.controller.data.source.request.DataSourceBaseRequest;
import ai.inquery.server.web.api.controller.rdb.converter.RdbWebConverter;
import ai.inquery.server.web.api.controller.rdb.request.*;
import ai.inquery.server.web.api.controller.rdb.vo.*;
import ai.inquery.spi.model.*;
import ai.inquery.spi.sql.InqueryContext;
import ai.inquery.spi.sql.ConnectInfo;
import com.google.common.collect.Lists;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * mysql table operation and maintenance class
 *
 * @version MysqlTableManageController.java, v 0.1 September 16, 2022 17:41 moji Exp $
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/ddl")
@RestController
@Slf4j
@Deprecated
public class RdbDdlController extends EmbeddingController {

    @Autowired
    private TableService tableService;

    @Autowired
    private DlTemplateService dlTemplateService;

    @Autowired
    private RdbWebConverter rdbWebConverter;

    @Autowired
    private DatabaseService databaseService;

    public static ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

    /**
     * Query the table list under the current DB
     *
     * @param request
     * @return
     */
    @GetMapping("/list")
    public WebPageResult<TableVO> list(@Valid TableBriefQueryRequest request) {
        TablePageQueryParam queryParam = rdbWebConverter.tablePageRequest2param(request);
        TableSelector tableSelector = new TableSelector();
        tableSelector.setColumnList(false);
        tableSelector.setIndexList(false);

        PageResult<Table> tableDTOPageResult = tableService.pageQuery(queryParam, tableSelector);
        List<TableVO> tableVOS = rdbWebConverter.tableDto2vo(tableDTOPageResult.getData());

//        ConnectInfo connectInfo = InqueryContext.getConnectInfo();
//        singleThreadExecutor.submit(() -> {
//            try {
//                InqueryContext.putContext(connectInfo);
//                syncTableVector(request);
////                syncTableEs(request);
//            } catch (Exception e) {
//                log.error("sync table vector error", e);
//            } finally {
//                InqueryContext.removeContext();
//            }
//            log.info("sync table vector finish");
//        });
        return WebPageResult.of(tableVOS, tableDTOPageResult.getTotal(), request.getPageNo(),
            request.getPageSize());
    }

    /**
     * Query the schema_list contained in the database
     *
     * @param request
     * @return
     */
    @GetMapping("/schema_list")
    public ListResult<SchemaVO> schemaList(@Valid DataSourceBaseRequest request) {
        SchemaQueryParam queryParam = SchemaQueryParam.builder().dataBaseName(request.getDatabaseName()).build();
        ListResult<Schema> tableColumns = databaseService.querySchema(queryParam);
        List<SchemaVO> tableVOS = rdbWebConverter.schemaDto2vo(tableColumns.getData());
        return ListResult.of(tableVOS);
    }

    /**
     * Query the database_schema_list contained in the database
     *
     * @param request
     * @return
     */
    @GetMapping("/database_schema_list")
    public DataResult<MetaSchemaVO> databaseSchemaList(@Valid DataSourceBaseRequest request) {
        MetaDataQueryParam queryParam = MetaDataQueryParam.builder().dataSourceId(request.getDataSourceId()).refresh(
            request.isRefresh()).build();
        DataResult<MetaSchema> result = databaseService.queryDatabaseSchema(queryParam);
        MetaSchemaVO schemaDto2vo = rdbWebConverter.metaSchemaDto2vo(result.getData());
        return DataResult.of(schemaDto2vo);
    }


    /**
     * Query the table columns under the current DB
     *
     * @param request
     * @return
     */
    @GetMapping("/column_list")
    public ListResult<ColumnVO> columnList(@Valid TableDetailQueryRequest request) {
        TableQueryParam queryParam = rdbWebConverter.tableRequest2param(request);
        List<TableColumn> tableColumns = tableService.queryColumns(queryParam);
        List<ColumnVO> tableVOS = rdbWebConverter.columnDto2vo(tableColumns);
        return ListResult.of(tableVOS);
    }

    /**
     * Query the table index under the current DB
     *
     * @param request
     * @return
     */
    @GetMapping("/index_list")
    public ListResult<IndexVO> indexList(@Valid TableDetailQueryRequest request) {
        TableQueryParam queryParam = rdbWebConverter.tableRequest2param(request);
        List<TableIndex> tableIndices = tableService.queryIndexes(queryParam);
        List<IndexVO> indexVOS = rdbWebConverter.indexDto2vo(tableIndices);
        return ListResult.of(indexVOS);
    }

    /**
     * Query the table key under the current DB
     *
     * @param request
     * @return
     */
    @GetMapping("/key_list")
    public ListResult<IndexVO> keyList(@Valid TableDetailQueryRequest request) {
        // TODO Add query key implementation
        return ListResult.of(Lists.newArrayList());
    }

    /**
     * Export table creation statement
     *
     * @param request
     * @return
     */
    @GetMapping("/export")
    public DataResult<String> export(@Valid DdlExportRequest request) {
        ShowCreateTableParam param = rdbWebConverter.ddlExport2showTableCreate(request);
        return tableService.showCreateTable(param);
    }

    /**
     * Table creation statement example
     *
     * @param request
     * @return
     */
    @GetMapping("/create/example")
    public DataResult<String> createExample(@Valid TableCreateDdlQueryRequest request) {
        return tableService.createTableExample(request.getDbType());
    }

    /**
     * Update table statement example
     *
     * @param request
     * @return
     */
    @GetMapping("/update/example")
    public DataResult<String> updateExample(@Valid TableUpdateDdlQueryRequest request) {
        return tableService.alterTableExample(request.getDbType());
    }

    /**
     * Get information such as table columns and indexes
     *
     * @param request
     * @return
     */
    @GetMapping("/query")
    public DataResult<TableVO> query(@Valid TableDetailQueryRequest request) {
        TableQueryParam queryParam = rdbWebConverter.tableRequest2param(request);
        TableSelector tableSelector = new TableSelector();
        tableSelector.setColumnList(true);
        tableSelector.setIndexList(true);
        DataResult<Table> tableDTODataResult = tableService.query(queryParam, tableSelector);
        TableVO tableVO = rdbWebConverter.tableDto2vo(tableDTODataResult.getData());
        return DataResult.of(tableVO);
    }

    /**
     * Get the sql statement that modifies the table
     *
     * @param request
     * @return
     */
    @GetMapping("/modify/sql")
    public ListResult<SqlVO> modifySql(@Valid TableModifySqlRequest request) {
        return tableService.buildSql(
                rdbWebConverter.tableRequest2param(request.getOldTable()),
                rdbWebConverter.tableRequest2param(request.getNewTable()))
            .map(rdbWebConverter::dto2vo);
    }

    /**
     * Delete table
     *
     * @param request
     * @return
     */
    @PostMapping("/delete")
    public ActionResult delete(@Valid @RequestBody TableDeleteRequest request) {
        DropParam dropParam = rdbWebConverter.tableDelete2dropParam(request);
        return tableService.drop(dropParam);
    }
}
