package ai.inquery.server.web.start.exception.convertor;

import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.common.util.I18nUtils;
import ai.inquery.spi.util.ExceptionUtils;

/**
 * Default exception handling
 * Throw system exception directly
 *
 */
public class DefaultExceptionConvertor implements ExceptionConvertor<Throwable> {

    @Override
    public ActionResult convert(Throwable exception) {
        return ActionResult.fail("common.systemError", I18nUtils.getMessage("common.systemError"), ExceptionUtils.getErrorInfoFromException(exception));
    }
}
