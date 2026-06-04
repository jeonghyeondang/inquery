package ai.inquery.server.web.api.controller.export;

import ai.inquery.server.domain.core.service.PdfGenerationService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController("pdfExportController")
@RequestMapping("/api/v1/export")
@Slf4j
public class ExportController {

    private final PdfGenerationService pdfGenerationService;

    @Autowired
    public ExportController(PdfGenerationService pdfGenerationService) {
        this.pdfGenerationService = pdfGenerationService;
    }

    @PostMapping("/pdf")
    public ResponseEntity<byte[]> exportPdf(@RequestBody PdfExportRequest request) {
        if (request == null || request.getHtml() == null || request.getHtml().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            byte[] pdfBytes = pdfGenerationService.generatePdfFromHtml(request.getHtml());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            // Example filename, the frontend usually defines its own download name though
            headers.setContentDispositionFormData("attachment", "export.pdf");

            return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Failed to export PDF", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @Data
    public static class PdfExportRequest {
        private String html;
    }
}
