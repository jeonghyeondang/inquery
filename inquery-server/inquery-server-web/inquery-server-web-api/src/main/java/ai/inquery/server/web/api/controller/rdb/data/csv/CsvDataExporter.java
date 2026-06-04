package ai.inquery.server.web.api.controller.rdb.data.csv;

import ai.inquery.server.domain.api.enums.ExportFileSuffix;
import ai.inquery.server.web.api.controller.rdb.data.BaseExcelExporter;
import com.alibaba.excel.support.ExcelTypeEnum;
import org.springframework.stereotype.Component;

/**
 * @date: 2024-06-04 10:05
 */
@Component("csvExporter")
public class CsvDataExporter extends BaseExcelExporter {


    public CsvDataExporter() {
        this.contentType = "text/csv";
        this.suffix = ExportFileSuffix.CSV.getSuffix();
    }


    @Override
    protected ExcelTypeEnum getExcelType() {
        return ExcelTypeEnum.CSV;
    }
}
