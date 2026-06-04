package ai.inquery.server.web.start.exception.convertor;

import ai.inquery.server.tools.base.wrapper.result.ActionResult;

/**
 * exception converter
 *
 */
public interface ExceptionConvertor<T extends Throwable> {

    /**
     * Conversion exception
     *
     * @param exception
     * @return
     */
    ActionResult convert(T exception);
}
