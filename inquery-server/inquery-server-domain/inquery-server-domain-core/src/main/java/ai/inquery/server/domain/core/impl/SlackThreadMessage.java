package ai.inquery.server.domain.core.impl;

import lombok.Data;

@Data
public class SlackThreadMessage {
    private String messageTs;
    private String messageText;
    private String userName;
    
    public SlackThreadMessage() {}
    
    public SlackThreadMessage(String messageTs, String messageText, String userName) {
        this.messageTs = messageTs;
        this.messageText = messageText;
        this.userName = userName;
    }
}







