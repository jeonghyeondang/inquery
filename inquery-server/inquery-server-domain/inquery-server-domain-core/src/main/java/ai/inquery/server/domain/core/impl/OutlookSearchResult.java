package ai.inquery.server.domain.core.impl;

import lombok.Data;

@Data
public class OutlookSearchResult {
    private String messageId;
    private String subject;
    private String body;
    private String from;
    private String to;
    private String receivedDateTime;
    private String webLink;
    private boolean hasAttachments;

    public OutlookSearchResult() {}

    public OutlookSearchResult(String messageId, String subject, String body, String from, String to, 
                              String receivedDateTime, String webLink, boolean hasAttachments) {
        this.messageId = messageId;
        this.subject = subject;
        this.body = body;
        this.from = from;
        this.to = to;
        this.receivedDateTime = receivedDateTime;
        this.webLink = webLink;
        this.hasAttachments = hasAttachments;
    }
}







