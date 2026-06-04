package ai.inquery.server.web.api.controller.rdb.data;

import ai.inquery.server.domain.api.enums.TaskStatusEnum;
import ai.inquery.server.domain.api.param.datasource.DatabaseExportDataParam;
import ai.inquery.server.web.api.controller.rdb.data.task.TaskManager;
import ai.inquery.spi.ValueProcessor;
import ai.inquery.spi.model.JDBCDataValue;
import ai.inquery.spi.sql.InqueryContext;
import ai.inquery.spi.sql.SQLExecutor;
import ai.inquery.spi.util.ResultSetUtils;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.write.builder.ExcelWriterSheetBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @date: 2024/06/04 10:56
 */
@Slf4j
public abstract class BaseExcelExporter extends BaseDataExporter {
    @Override
    protected void singleExport(Connection connection, DatabaseExportDataParam exportParam, File outputFile) {
        ExcelTypeEnum excelType = getExcelType();
        try (OutputStream outputStream = new FileOutputStream(outputFile)) {


            String tableName = exportParam.getTableNames().get(0);
            String querySql = getQuerySql(exportParam, tableName);

            log.info("Start exporting: {} table data, export type: {}", tableName, excelType);

            SQLExecutor.getInstance().execute(connection, querySql, BATCH_SIZE, resultSet ->
                    writeExcelData(resultSet, excelType, outputStream, tableName, exportParam.getContainsHeader()));

        } catch (IOException e) {
            TaskManager.updateStatus(TaskStatusEnum.ERROR);
            throw new RuntimeException(e);
        }
    }

    @Override
    protected ByteArrayOutputStream multiExport(Connection connection, DatabaseExportDataParam databaseExportDataParam, String tableName) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ExcelTypeEnum excelType = getExcelType();

        log.info("Start exporting: {} table data, export type: {}", tableName, excelType);

        String querySql = getQuerySql(databaseExportDataParam, tableName);
        SQLExecutor.getInstance().execute(connection, querySql, BATCH_SIZE, resultSet -> {
            writeExcelData(resultSet, excelType, byteArrayOutputStream, tableName, databaseExportDataParam.getContainsHeader());
        });
        return byteArrayOutputStream;
    }

    private void writeExcelData(ResultSet resultSet, ExcelTypeEnum excelType, OutputStream outputStream, String sheetName, Boolean containsHeader) {
        try {
            ExcelWriterSheetBuilder excelWriterSheetBuilder = EasyExcel.write(outputStream).excelType(excelType).sheet(sheetName);
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            ValueProcessor valueProcessor = InqueryContext.getMetaData().getValueProcessor();
            List<List<Object>> dataList = new ArrayList<>();

            if (containsHeader) {
                List<String> header = ResultSetUtils.getRsHeader(resultSet);
                excelWriterSheetBuilder.head(header.stream().map(Collections::singletonList).collect(Collectors.toList()));
            }

            while (resultSet.next()) {
                List<Object> rowDataList = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    JDBCDataValue jdbcDataValue = new JDBCDataValue(resultSet, metaData, i, false);
                    rowDataList.add(valueProcessor.getJdbcValue(jdbcDataValue));
                }
                dataList.add(rowDataList);
            }

            excelWriterSheetBuilder.doWrite(dataList);
            TaskManager.increaseCurrent();
        } catch (SQLException e) {
            TaskManager.updateStatus(TaskStatusEnum.ERROR);
            log.error("Error writing Excel data", e);
            throw new RuntimeException(e);
        }
    }

    private String getQuerySql(DatabaseExportDataParam databaseExportDataParam, String tableName) {
        String databaseName = databaseExportDataParam.getDatabaseName();
        String schemaName = databaseExportDataParam.getSchemaName();
        return InqueryContext.getSqlBuilder().buildTableQuerySql(databaseName, schemaName, tableName);
    }

    protected abstract ExcelTypeEnum getExcelType();
}

