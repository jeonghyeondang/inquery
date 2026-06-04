package ai.inquery.server.web.api.controller.rdb.data.factory;

import ai.inquery.server.tools.common.exception.ParamBusinessException;
import ai.inquery.server.web.api.controller.rdb.data.DataImportStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * @date: 2024-06-04 10:07
 */
@Component
public class DataImportFactory {


    private static final String BEAN_SUFFIX = "Importer";
    private final Map<String, DataImportStrategy> imports;

    @Autowired
    public DataImportFactory(Map<String, DataImportStrategy> imports) {
        this.imports = imports;
    }

    public DataImportStrategy getImporter(String type) {
        DataImportStrategy dataImportStrategy = imports.get(type.toLowerCase() + BEAN_SUFFIX);
        if (Objects.isNull(dataImportStrategy)) {
            throw new ParamBusinessException(type);
        }
        return dataImportStrategy;
    }

}
