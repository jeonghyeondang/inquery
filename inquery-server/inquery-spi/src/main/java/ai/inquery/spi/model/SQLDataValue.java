package ai.inquery.spi.model;

import com.google.common.io.BaseEncoding;
import lombok.Data;

/**
 * @date: 2024-05-30 15:01
 */
@Data
public class SQLDataValue {
    private String value;
    private DataType dataType;

    public String getDateTypeName() {
        return dataType.getDataTypeName();
    }

    public int getPrecision() {
        return dataType.getPrecision();
    }

    public int getScale() {
        return dataType.getScale();
    }

    public String getBlobHexString() {
        return BaseEncoding.base16().encode(value.getBytes());
    }
}
