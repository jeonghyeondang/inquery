package ai.inquery.server.web.start.exception.convertor;

import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.spi.util.ExceptionUtils;

/**
 * Parameter exceptions currently include: 
 * ConstraintViolationException
 * MissingServletRequestParameterException
 * IllegalArgumentException
 *
 */
public class ParamExceptionConvertor implements ExceptionConvertor<Throwable> {

    @Override
    public ActionResult convert(Throwable exception) {
        return ActionResult.fail("common.paramError", exception.getMessage(), ExceptionUtils.getErrorInfoFromException(exception));
    }
}
