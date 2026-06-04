package ai.inquery.server.tools.common.exception;

import java.io.Serial;

import ai.inquery.server.tools.base.constant.EasyToolsConstant;
import ai.inquery.server.tools.base.excption.BusinessException;
import lombok.Getter;

/**
 * User login exception
 *
 */
@Getter
public class NeedLoggedInBusinessException extends BusinessException {

    @Serial
    private static final long serialVersionUID = EasyToolsConstant.SERIAL_VERSION_UID;

    public NeedLoggedInBusinessException() {
        super("common.needLoggedIn");
    }
}