package ai.inquery.server.tools.common.exception;

import java.io.Serial;

import ai.inquery.server.tools.base.constant.EasyToolsConstant;
import ai.inquery.server.tools.base.excption.BusinessException;
import lombok.Getter;

/**
 * Data already exists exception
 *
 */
@Getter
public class DataAlreadyExistsBusinessException extends BusinessException {

    @Serial
    private static final long serialVersionUID = EasyToolsConstant.SERIAL_VERSION_UID;

    public DataAlreadyExistsBusinessException() {
        super("common.dataAlreadyExists");
    }

    public DataAlreadyExistsBusinessException(String key, Object value) {
        super("common.dataAlreadyExistsWithParam", new Object[] {key, value});
    }
}