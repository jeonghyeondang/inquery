package ai.inquery.server.domain.core.attachment;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Extracts plain text from attachments for two purposes:
 * <ol>
 *     <li>In-chat search / preview rendering (always populated when we
 *         have a sensible textual representation).</li>
 *     <li>Fallback prompt content when the active LLM lacks native PDF
 *         support (see {@code ModelCapabilities}).</li>
 * </ol>
 *
 * <p>Output is hard-capped at {@link #MAX_CHARS} characters (head + tail
 * window) so we never blow up the model context window for an
 * accidentally pasted multi-MB log file.
 */
@Slf4j
public final class AttachmentTextExtractor {

    /** ~200KB worth of UTF-8; safe across model context windows. */
    public static final int MAX_CHARS = 200_000;
    private static final int HEAD_CHARS = 150_000;
    private static final int TAIL_CHARS = 40_000;

    private AttachmentTextExtractor() {}

    /** Try to extract text. Returns {@code null} for images. */
    public static String extract(String kind, String mimeType, byte[] bytes) {
        return clip(extractRaw(kind, mimeType, bytes));
    }

    /**
     * Full text extraction for reference-document indexing (no chat clip).
     * Still capped at {@link #MAX_REFERENCE_CHARS} to protect DB size.
     */
    public static final int MAX_REFERENCE_CHARS = 2_000_000;

    public static String extractForReference(String kind, String mimeType, byte[] bytes) {
        return clipReference(extractRaw(kind, mimeType, bytes));
    }

    private static String extractRaw(String kind, String mimeType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            if ("pdf".equals(kind)) {
                return extractPdf(bytes);
            }
            if ("office".equals(kind)) {
                return extractOffice(mimeType, bytes);
            }
            if ("text".equals(kind)) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            return null;
        } catch (Exception e) {
            log.warn("Text extraction failed (kind={}, mime={}, size={}): {}",
                    kind, mimeType, bytes.length, e.getMessage());
            return null;
        }
    }

    private static String clipReference(String s) {
        if (s == null) return null;
        if (s.length() <= MAX_REFERENCE_CHARS) return s;
        return s.substring(0, MAX_REFERENCE_CHARS)
                + "\n\n[... truncated for storage limit ...]\n";
    }

    private static String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(bytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    /**
     * Extracts text from Microsoft Office OOXML formats (pptx / docx /
     * xlsx). Legacy binary formats (.ppt / .doc / .xls) aren't covered
     * here — they need {@code poi-scratchpad} which we deliberately
     * don't pull in to keep the dependency graph small.
     */
    private static String extractOffice(String mimeType, byte[] bytes) throws Exception {
        String mime = mimeType == null ? "" : mimeType.toLowerCase();
        if (mime.contains("presentationml") || mime.contains("powerpoint")) {
            return extractPptx(bytes);
        }
        if (mime.contains("wordprocessingml") || mime.contains("msword")) {
            return extractDocx(bytes);
        }
        if (mime.contains("spreadsheetml") || mime.contains("excel")) {
            return extractXlsx(bytes);
        }
        return null;
    }

    private static String extractPptx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            int idx = 1;
            for (XSLFSlide slide : ppt.getSlides()) {
                sb.append("=== Slide ").append(idx++);
                String title = slide.getTitle();
                if (title != null && !title.isBlank()) {
                    sb.append(" — ").append(title.trim());
                }
                sb.append(" ===\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape ts) {
                        String txt = ts.getText();
                        if (txt != null && !txt.isBlank()) {
                            sb.append(txt.strip()).append('\n');
                        }
                    }
                }
                XSLFNotes notes = slide.getNotes();
                if (notes != null) {
                    StringBuilder notesText = new StringBuilder();
                    for (XSLFShape shape : notes.getShapes()) {
                        if (shape instanceof XSLFTextShape ts) {
                            String txt = ts.getText();
                            if (txt != null && !txt.isBlank()) {
                                notesText.append(txt.strip()).append('\n');
                            }
                        }
                    }
                    if (notesText.length() > 0) {
                        sb.append("[notes] ").append(notesText);
                    }
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String extractDocx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) sb.append(t).append('\n');
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    boolean first = true;
                    for (XWPFTableCell cell : row.getTableCells()) {
                        if (!first) sb.append(" | ");
                        sb.append(cell.getText());
                        first = false;
                    }
                    sb.append('\n');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String extractXlsx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        DataFormatter fmt = new DataFormatter();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : wb) {
                sb.append("=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");
                for (Row row : sheet) {
                    boolean first = true;
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        if (!first) sb.append('\t');
                        sb.append(fmt.formatCellValue(row.getCell(c)));
                        first = false;
                    }
                    sb.append('\n');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * If the input is longer than {@link #MAX_CHARS}, keep
     * {@link #HEAD_CHARS} from the start, {@link #TAIL_CHARS} from the
     * end, and drop a marker in between so the LLM sees that something
     * was elided rather than just silently losing data.
     */
    private static String clip(String s) {
        if (s == null) return null;
        if (s.length() <= MAX_CHARS) return s;
        int dropped = s.length() - HEAD_CHARS - TAIL_CHARS;
        return s.substring(0, HEAD_CHARS)
                + "\n\n[... truncated " + dropped + " chars ...]\n\n"
                + s.substring(s.length() - TAIL_CHARS);
    }
}
