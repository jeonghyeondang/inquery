package ai.inquery.server.domain.core.reference;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits extracted document text into overlapping chunks for vector indexing.
 */
public final class ReferenceDocumentChunker {

    private static final int CHUNK_SIZE = 1_500;
    private static final int OVERLAP = 200;

    private ReferenceDocumentChunker() {}

    public static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (StringUtils.isBlank(text)) {
            return chunks;
        }
        String normalized = text.replace("\r\n", "\n").strip();
        if (normalized.length() <= CHUNK_SIZE) {
            chunks.add(normalized);
            return chunks;
        }

        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + CHUNK_SIZE, normalized.length());
            if (end < normalized.length()) {
                int breakAt = findBreak(normalized, start, end);
                if (breakAt > start) {
                    end = breakAt;
                }
            }
            String piece = normalized.substring(start, end).strip();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - OVERLAP, start + 1);
        }
        return chunks;
    }

    private static int findBreak(String text, int start, int end) {
        for (int i = end - 1; i > start + CHUNK_SIZE / 2; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '.' || c == ' ') {
                return i + 1;
            }
        }
        return end;
    }
}
