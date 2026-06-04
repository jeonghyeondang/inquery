package ai.inquery.server.tools.common.exception;

import java.io.Serial;

import ai.inquery.server.tools.base.constant.EasyToolsConstant;
import ai.inquery.server.tools.base.excption.BusinessException;
import lombok.Getter;

/**
 * Business exceptions that require redirection
 *
 */
@Getter
public class RedirectBusinessException extends BusinessException {

    @Serial
    private static final long serialVersionUID = EasyToolsConstant.SERIAL_VERSION_UID;
    private final String redirect;

    public RedirectBusinessException(String redirect) {
        super("common.redirect");
        this.redirect = redirect;
    }
}