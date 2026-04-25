package tgbotgpt.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;

@Slf4j
@Service
public class DocumentService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "txt");
    private static final String TELEGRAM_HOST = "api.telegram.org";

    @Value("${bot.document.max.size.mb:10}")
    private int maxSizeMb;

    @Value("${bot.document.max.text.chars:15000}")
    private int maxTextChars;

    @Value("${bot.document.max.pages:50}")
    private int maxPages;

    @Value("${bot.document.parse.timeout.seconds:30}")
    private int parseTimeoutSeconds;

    @Value("${bot.file.download.timeout.seconds:15}")
    private int downloadTimeoutSeconds;

    public DocumentExtractionResult extractText(String fileUrl, String fileName) {
        String extension = getExtension(fileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            log.warn("Unsupported document extension: {}", extension);
            return DocumentExtractionResult.unsupportedType();
        }

        try {
            URI uri = URI.create(fileUrl);
            if (!"https".equals(uri.getScheme()) || !isTrustedTelegramHost(uri.getHost())) {
                log.warn("Rejected non-Telegram document URL: {}", uri.getHost());
                return DocumentExtractionResult.invalidSource();
            }

            byte[] data = download(uri, maxSizeMb * 1024 * 1024 + 1);
            if (data.length > maxSizeMb * 1024 * 1024) {
                log.warn("Document too large: {} bytes", data.length);
                return DocumentExtractionResult.tooLarge();
            }

            String text = switch (extension) {
                case "pdf" -> extractFromPdf(data);
                case "txt" -> new String(data, StandardCharsets.UTF_8);
                default -> null;
            };

            if (text == null || text.isBlank()) {
                return DocumentExtractionResult.unreadable();
            }

            // Trim to max chars to avoid blowing up GPT context
            if (text.length() > maxTextChars) {
                text = text.substring(0, maxTextChars) + "\n\n[... truncated, " + maxTextChars + " chars limit]";
            }

            return DocumentExtractionResult.success(text);
        } catch (DocumentParsingTimeoutException e) {
            return DocumentExtractionResult.timeout();
        } catch (Exception e) {
            log.error("Failed to process document: ", e);
            return DocumentExtractionResult.unreadable();
        }
    }

    private byte[] download(URI uri, int maxBytesPlusOne) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(downloadTimeoutSeconds))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(downloadTimeoutSeconds))
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Telegram file download failed with status " + response.statusCode());
        }

        try (InputStream in = response.body()) {
            return in.readNBytes(maxBytesPlusOne);
        }
    }

    private String extractFromPdf(byte[] data) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            try (PDDocument doc = Loader.loadPDF(data)) {
                int pages = doc.getNumberOfPages();
                if (pages > maxPages) {
                    log.warn("PDF has too many pages: {} (max {})", pages, maxPages);
                    return null;
                }

                PDFTextStripper stripper = new PDFTextStripper();
                return stripper.getText(doc);
            }
        });

        try {
            return future.get(parseTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("PDF parsing timed out after {} seconds", parseTimeoutSeconds);
            throw new DocumentParsingTimeoutException();
        } catch (OutOfMemoryError e) {
            log.error("PDF parsing ran out of memory");
            return null;
        } catch (Exception e) {
            log.error("Failed to extract PDF text: ", e);
            return null;
        } finally {
            executor.shutdownNow();
        }
    }

    public String getExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "";
        return fileName.substring(dot + 1).toLowerCase();
    }

    public boolean isSupported(String fileName) {
        return ALLOWED_EXTENSIONS.contains(getExtension(fileName));
    }

    private boolean isTrustedTelegramHost(String host) {
        return host != null && TELEGRAM_HOST.equalsIgnoreCase(host);
    }

    private static final class DocumentParsingTimeoutException extends RuntimeException {
    }
}
