package ai.inquery.server.web.api.controller.rdb.doc.export;

import ai.inquery.server.domain.api.enums.ExportFileSuffix;
import ai.inquery.server.domain.api.enums.ExportTypeEnum;
import ai.inquery.server.domain.api.model.TableParameter;
import ai.inquery.server.tools.common.util.I18nUtils;
import ai.inquery.server.web.api.controller.rdb.doc.DatabaseExportService;
import ai.inquery.server.web.api.controller.rdb.doc.adaptive.CustomCellWriteHeightConfig;
import ai.inquery.server.web.api.controller.rdb.doc.adaptive.CustomCellWriteWidthConfig;
import ai.inquery.server.web.api.controller.rdb.doc.conf.ExportOptions;
import ai.inquery.server.web.api.controller.rdb.doc.merge.MyMergeExcel;
import ai.inquery.server.web.api.controller.rdb.doc.style.CustomExcelStyle;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.write.style.HorizontalCellStyleStrategy;
import lombok.SneakyThrows;
import lombok.val;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ExportExcelService
 *
 **/
public class ExportExcelService extends DatabaseExportService {

    public ExportExcelService() {
        exportTypeEnum = ExportTypeEnum.EXCEL;
        suffix = ExportFileSuffix.EXCEL.getSuffix();
        contentType = "text/csv";
    }

    @SneakyThrows
    @Override
    public void export(OutputStream outputStream, ExportOptions exportOptions) {
        List<TableParameter> export = new ArrayList<>();
        for (Map.Entry<String, List<TableParameter>> item : listMap.entrySet()) {
            val t = new TableParameter();
            t.setNo(item.getKey()).setColumnComment(MyMergeExcel.NAME);
            export.add(t);
            export.addAll(item.getValue());
        }
        EasyExcel.write(outputStream)
                .registerWriteHandler(new HorizontalCellStyleStrategy(CustomExcelStyle.getHeadStyle(), CustomExcelStyle.getContentWriteCellStyle()))
                .registerWriteHandler(new CustomCellWriteHeightConfig())
                .registerWriteHandler(new CustomCellWriteWidthConfig())
                .registerWriteHandler(new MyMergeExcel())
                .sheet(I18nUtils.getMessage("main.sheetName"))
                .doWrite(export);
    }

}
