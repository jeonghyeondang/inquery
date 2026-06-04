package ai.inquery.server.web.api.controller.rdb;

import ai.inquery.server.domain.api.param.*;
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
import ai.inquery.server.web.api.controller.rdb.converter.RdbWebConverter;
import ai.inquery.server.web.api.controller.rdb.request.*;
import ai.inquery.server.web.api.controller.rdb.vo.ColumnVO;
import ai.inquery.server.web.api.controller.rdb.vo.IndexVO;
import ai.inquery.server.web.api.controller.rdb.vo.SqlVO;
import ai.inquery.server.web.api.controller.rdb.vo.TableVO;
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

@Slf4j
@ConnectionInfoAspect
@RequestMapping("/api/rdb/table")
@RestController
public class TableController extends EmbeddingController {

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
        
        // Auto-sync table vectors to Pinecone in background
        ConnectInfo connectInfo = InqueryContext.getConnectInfo();
        singleThreadExecutor.submit(() -> {
            try {
                // Set up MyBatis SqlSession for this thread
                ai.inquery.server.domain.repository.Dbutils.setSession();
                // Set up database connection context
                InqueryContext.putContext(connectInfo);
                log.info("TableController: Starting syncTableVector for dataSourceId: {}, databaseName: {}, schemaName: {}", 
                    request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName());
                syncTableVector(request);
                log.info("TableController: syncTableVector completed successfully");
            } catch (Exception e) {
                log.error("TableController: sync table vector error - dataSourceId: {}, databaseName: {}, schemaName: {}", 
                    request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName(), e);
            } finally {
                // Clean up contexts
                InqueryContext.removeContext();
                ai.inquery.server.domain.repository.Dbutils.removeSession();
            }
            log.info("TableController: sync table vector finish");
        });
        
        return WebPageResult.of(tableVOS, tableDTOPageResult.getTotal(), request.getPageNo(),
                request.getPageSize());
    }

    /**
     * Query the table list under the current DB
     *
     * @param request
     * @return
     */
    @GetMapping("/table_list")
    public ListResult<SimpleTable> tableList(@Valid TableBriefQueryRequest request) {
        TablePageQueryParam queryParam = rdbWebConverter.tablePageRequest2param(request);
        return tableService.queryTables(queryParam);

    }

    /**
     * Query all tables from all databases using INFORMATION_SCHEMA
     *
     * @param request
     * @return
     */
    @GetMapping("/all_tables")
    public ListResult<SimpleTable> allTables(@Valid TableBriefQueryRequest request) {
        TablePageQueryParam queryParam = rdbWebConverter.tablePageRequest2param(request);
        return tableService.queryAllTablesFromAllDatabases(queryParam);
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

    @GetMapping("/copy_dml_sql")
    public DataResult<String> copyDmlSql(@Valid DmlSqlCopyRequest request) {
        DmlSqlCopyParam queryParam = rdbWebConverter.dmlRequest2param(request);
        return tableService.copyDmlSql(queryParam);
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
    public DataResult<Table> query(@Valid TableDetailQueryRequest request) {
        TableQueryParam queryParam = rdbWebConverter.tableRequest2param(request);
        TableSelector tableSelector = new TableSelector();
        tableSelector.setColumnList(true);
        tableSelector.setIndexList(true);
        return tableService.query(queryParam, tableSelector);
        //TableVO tableVO = rdbWebConverter.tableDto2vo(tableDTODataResult.getData());
        //return DataResult.of(tableVO);
    }

    /**
     * Get the sql statement that modifies the table
     *
     * @param request
     * @return
     */
    @PostMapping("/modify/sql")
    public ListResult<SqlVO> modifySql(@Valid @RequestBody TableModifySqlRequest request) {
        Table table =  rdbWebConverter.tableRequest2param(request.getNewTable());
        table.setSchemaName(request.getSchemaName());
        table.setDatabaseName(request.getDatabaseName());
        for (TableColumn tableColumn : table.getColumnList()) {
            tableColumn.setSchemaName(request.getSchemaName());
            tableColumn.setTableName(table.getName());
            tableColumn.setDatabaseName(request.getDatabaseName());
        }
        for (TableIndex tableIndex : table.getIndexList()) {
            tableIndex.setSchemaName(request.getSchemaName());
            tableIndex.setTableName(table.getName());
            tableIndex.setDatabaseName(request.getDatabaseName());
        }

        return tableService.buildSql(rdbWebConverter.tableRequest2param(request.getOldTable()),table)
                .map(rdbWebConverter::dto2vo);
    }



    /**
     * Data types supported by the database
     *
     * @param request
     * @return
     */
    @GetMapping("/type_list")
    public ListResult<Type> types(@Valid TypeQueryRequest request) {
        TypeQueryParam typeQueryParam = TypeQueryParam.builder().dataSourceId(request.getDataSourceId()).build();
        List<Type> types = tableService.queryTypes(typeQueryParam);
        return ListResult.of(types);
    }


    @GetMapping("/table_meta")
    public DataResult<TableMeta> tableMeta(@Valid TypeQueryRequest request) {
        TypeQueryParam typeQueryParam = TypeQueryParam.builder().dataSourceId(request.getDataSourceId()).build();
        TableMeta tableMeta = tableService.queryTableMeta(typeQueryParam);
        return DataResult.of(tableMeta);
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
