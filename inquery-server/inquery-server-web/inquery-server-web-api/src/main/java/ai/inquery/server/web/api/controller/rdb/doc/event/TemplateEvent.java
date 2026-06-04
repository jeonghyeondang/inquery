package ai.inquery.server.web.api.controller.rdb.doc.event;

import org.springframework.context.ApplicationEvent;

/**
 * TemplateEvent
 *
 **/
public class TemplateEvent extends ApplicationEvent {
    public TemplateEvent(String key) {
        super(key);
    }
}
