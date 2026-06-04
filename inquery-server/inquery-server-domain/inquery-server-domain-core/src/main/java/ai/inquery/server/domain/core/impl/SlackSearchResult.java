package ai.inquery.server.domain.core.impl;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class SlackSearchResult {
    private String channelId;
    private String channelName;
    private String messageTs;
    private String messageText;
    private String messageUrl;
    private String userName;
    private List<SlackThreadMessage> threadMessages;
    
    public SlackSearchResult() {
        this.threadMessages = new ArrayList<>();
    }
    
    public SlackSearchResult(String channelId, String channelName, String messageTs, 
                           String messageText, String messageUrl, String userName) {
        this.channelId = channelId;
        this.channelName = channelName;
        this.messageTs = messageTs;
        this.messageText = messageText;
        this.messageUrl = messageUrl;
        this.userName = userName;
        this.threadMessages = new ArrayList<>();
    }
}







