package ai.inquery.server.domain.core.impl;

/**
 * Normalized Google Drive search hit (Docs / Sheets).
 */
public class GoogleDriveSearchResult {

    private final String fileId;
    private final String title;
    private final String mimeType;
    private final String url;
    private String content;

    public GoogleDriveSearchResult(String fileId, String title, String mimeType, String url, String content) {
        this.fileId = fileId;
        this.title = title;
        this.mimeType = mimeType;
        this.url = url;
        this.content = content;
    }

    public String getFileId() {
        return fileId;
    }

    public String getTitle() {
        return title;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getUrl() {
        return url;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
