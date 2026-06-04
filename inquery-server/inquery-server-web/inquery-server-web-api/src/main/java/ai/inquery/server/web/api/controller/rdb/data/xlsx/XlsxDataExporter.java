package ai.inquery.server.web.api.controller.rdb.data.xlsx;

import ai.inquery.server.domain.api.enums.ExportFileSuffix;
import ai.inquery.server.web.api.controller.rdb.data.BaseExcelExporter;
import com.alibaba.excel.support.ExcelTypeEnum;
import org.springframework.stereotype.Component;

/**
 * @date: 2024-06-04 10:34
 */
@Component("xlsxExporter")
public class XlsxDataExporter extends BaseExcelExporter {

    public XlsxDataExporter() {
        this.suffix = ExportFileSuffix.EXCEL.getSuffix();
        this.contentType="application/vnd.ms-excel";
    }


    @Override
    protected ExcelTypeEnum getExcelType() {
        return ExcelTypeEnum.XLSX;
    }
}
