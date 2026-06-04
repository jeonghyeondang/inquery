package ai.inquery.server.tools.common.exception;

import java.io.Serial;

import ai.inquery.server.tools.base.constant.EasyToolsConstant;
import ai.inquery.server.tools.base.excption.BusinessException;
import lombok.Getter;

/**
 * Permission Denied
 *
 */
@Getter
public class PermissionDeniedBusinessException extends BusinessException {

    @Serial
    private static final long serialVersionUID = EasyToolsConstant.SERIAL_VERSION_UID;

    public PermissionDeniedBusinessException() {
        super("common.permissionDenied");
    }
}