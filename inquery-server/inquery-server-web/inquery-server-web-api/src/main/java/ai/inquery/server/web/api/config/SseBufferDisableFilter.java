package ai.inquery.server.web.api.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Filter to disable response buffering for SSE endpoints.
 * This ensures real-time streaming of SSE events to the client.
 */
@Component
@Order(1)
public class SseBufferDisableFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String uri = httpRequest.getRequestURI();

        // Only apply to SSE streaming endpoints
        if (uri.contains("/agent/chat/stream") || uri.contains("/stream/chat")) {
            HttpServletResponse httpResponse = (HttpServletResponse) response;

            // Disable buffering headers
            httpResponse.setHeader("X-Accel-Buffering", "no");
            httpResponse.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            httpResponse.setHeader("Pragma", "no-cache");
            httpResponse.setHeader("Expires", "0");

            // Set buffer size to 0 to disable buffering
            httpResponse.setBufferSize(0);

            // Wrap response to auto-flush on every write
            FlushingResponseWrapper wrappedResponse = new FlushingResponseWrapper(httpResponse);
            chain.doFilter(request, wrappedResponse);
        } else {
            chain.doFilter(request, response);
        }
    }

    /**
     * Response wrapper that flushes after every write operation.
     */
    private static class FlushingResponseWrapper extends HttpServletResponseWrapper {

        public FlushingResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            return new FlushingServletOutputStream(super.getOutputStream());
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            return new FlushingPrintWriter(super.getWriter());
        }
    }

    /**
     * ServletOutputStream that flushes after every write.
     */
    private static class FlushingServletOutputStream extends ServletOutputStream {
        private final ServletOutputStream delegate;

        public FlushingServletOutputStream(ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            delegate.flush();
        }

        @Override
        public void write(byte[] b) throws IOException {
            delegate.write(b);
            delegate.flush();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            delegate.flush();
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            delegate.setWriteListener(listener);
        }
    }

    /**
     * PrintWriter that flushes after every write.
     */
    private static class FlushingPrintWriter extends PrintWriter {
        public FlushingPrintWriter(PrintWriter delegate) {
            super(delegate, true); // auto-flush enabled
        }

        @Override
        public void write(int c) {
            super.write(c);
            super.flush();
        }

        @Override
        public void write(char[] buf, int off, int len) {
            super.write(buf, off, len);
            super.flush();
        }

        @Override
        public void write(String s, int off, int len) {
            super.write(s, off, len);
            super.flush();
        }
    }
}
