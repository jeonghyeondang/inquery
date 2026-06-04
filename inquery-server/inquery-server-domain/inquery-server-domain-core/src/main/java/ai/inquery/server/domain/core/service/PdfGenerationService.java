package ai.inquery.server.domain.core.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Map;

/**
 * Lazy-initialized Playwright PDF generation service.
 * Playwright + Chromium are only created on the first PDF request,
 * avoiding zombie child processes during normal server lifecycle.
 */
@Service
@Slf4j
public class PdfGenerationService {

    private volatile Playwright playwright;
    private volatile Browser browser;
    private volatile Thread initThread;
    private volatile boolean initialized = false;
    private volatile boolean initFailed = false;
    private final Object lock = new Object();

    /**
     * Lazy init: creates Playwright + browser only when first needed.
     * Thread-safe via double-checked locking.
     */
    private void ensureInitialized() {
        if (initialized && browser != null && browser.isConnected()) {
            return;
        }
        synchronized (lock) {
            if (initialized && browser != null && browser.isConnected()) {
                return;
            }
            if (initFailed) {
                throw new IllegalStateException("Playwright initialization previously failed. Restart the server to retry.");
            }
            doInitialize();
        }
    }

    private void doInitialize() {
        initThread = Thread.currentThread();
        log.info("Initializing Playwright browser for PDF generation (lazy, on first request)...");
        try {
            this.playwright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("NODE_TLS_REJECT_UNAUTHORIZED", "0")));

            this.browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            log.info("Playwright bundled Chromium browser launched.");
            initialized = true;
        } catch (Exception e) {
            initFailed = true;
            log.error("Failed to initialize Playwright. PDF export unavailable.", e);
            safeClose();
            throw new IllegalStateException("Playwright initialization failed.", e);
        } finally {
            initThread = null;
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down Playwright...");
        Thread t = this.initThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        safeClose();
        log.info("Playwright shutdown complete.");
    }

    private void safeClose() {
        try {
            if (this.browser != null) {
                this.browser.close();
                this.browser = null;
            }
        } catch (Exception e) {
            log.warn("Error closing Playwright browser: {}", e.getMessage());
        }
        try {
            if (this.playwright != null) {
                this.playwright.close();
                this.playwright = null;
            }
        } catch (Exception e) {
            log.warn("Error closing Playwright: {}", e.getMessage());
        }
        initialized = false;
    }

    private static final String PRINT_CSS = String.join("\n",
            "@media print {",
            "  body { -webkit-print-color-adjust: exact !important; print-color-adjust: exact !important; }",
            "  * { overflow: visible !important; }",
            "  p, li, blockquote { orphans: 3; widows: 3; }",
            "  h1, h2, h3, h4 { page-break-after: avoid; break-after: avoid; orphans: 3; widows: 3; }",
            "  tr { page-break-inside: avoid; break-inside: avoid; }",
            "  thead { display: table-header-group; }",
            "  img, svg, canvas, figure { page-break-inside: avoid; break-inside: avoid; max-height: 90vh; }",
            "  pre, code { page-break-inside: avoid; break-inside: avoid; white-space: pre-wrap; word-wrap: break-word; }",
            "  .page-break { page-break-before: always; break-before: always; }",
            "}"
    );

    /**
     * Converts raw HTML content into a PDF byte array (A4 format with pagination).
     * First call triggers Playwright initialization (may take a few seconds).
     */
    public byte[] generatePdfFromHtml(String htmlContent) {
        ensureInitialized();

        try (var context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1200, 1600));
             Page page = context.newPage()) {

            page.setContent(htmlContent, new Page.SetContentOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.NETWORKIDLE));

            page.waitForTimeout(2000);

            page.evaluate("(css) => {" +
                    "  const style = document.createElement('style');" +
                    "  style.textContent = css;" +
                    "  document.head.appendChild(style);" +
                    "  document.querySelectorAll('[class*=\"chart\"]').forEach(el => {" +
                    "    if (el.getBoundingClientRect().height < 100) el.style.minHeight = '300px';" +
                    "  });" +
                    "}", PRINT_CSS);

            page.waitForTimeout(500);

            return page.pdf(new Page.PdfOptions()
                    .setFormat("A4")
                    .setPrintBackground(true)
                    .setDisplayHeaderFooter(true)
                    .setHeaderTemplate("<span></span>")
                    .setFooterTemplate("<div style='font-size:9px;color:#94a3b8;text-align:center;width:100%;'><span class='pageNumber'></span> / <span class='totalPages'></span></div>")
                    .setMargin(new com.microsoft.playwright.options.Margin()
                            .setTop("48px")
                            .setRight("48px")
                            .setBottom("60px")
                            .setLeft("48px")));
        } catch (Exception e) {
            log.error("Error generating PDF from HTML.", e);
            throw new RuntimeException("Failed to generate PDF.", e);
        }
    }
}
