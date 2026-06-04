package ai.inquery.server.web.api.controller.rdb.data.factory;

import ai.inquery.server.tools.common.exception.ParamBusinessException;
import ai.inquery.server.web.api.controller.rdb.data.DataExportStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * @date: 2024-06-04 10:26
 */
@Component
public class DataExportFactory {

    public static final String BEAN_SUFFIX = "Exporter";
    private final Map<String, DataExportStrategy> exports;

    @Autowired
    public DataExportFactory(Map<String, DataExportStrategy> exports) {
        this.exports = exports;
    }

    public DataExportStrategy getExporter(String type) {
        DataExportStrategy dataExportStrategy = exports.get(type.toLowerCase() + BEAN_SUFFIX);
        if (Objects.isNull(dataExportStrategy)) {
            throw new ParamBusinessException(type);
        }
        return dataExportStrategy;
    }
}
