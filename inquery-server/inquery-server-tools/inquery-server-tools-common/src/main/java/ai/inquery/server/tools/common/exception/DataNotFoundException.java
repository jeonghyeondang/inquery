package ai.inquery.server.tools.common.exception;

import java.io.Serial;

import ai.inquery.server.tools.base.constant.EasyToolsConstant;
import ai.inquery.server.tools.base.excption.BusinessException;
import lombok.Getter;

/**
 * Data not found exceptions
 *
 */
@Getter
public class DataNotFoundException extends BusinessException {

    @Serial
    private static final long serialVersionUID = EasyToolsConstant.SERIAL_VERSION_UID;

    public DataNotFoundException() {
        super("common.dataNotFound");
    }

}