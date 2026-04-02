package tgbotgpt.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class DocumentServiceTest {

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService();
        ReflectionTestUtils.setField(documentService, "maxSizeMb", 10);
        ReflectionTestUtils.setField(documentService, "maxTextChars", 15000);
        ReflectionTestUtils.setField(documentService, "maxPages", 50);
        ReflectionTestUtils.setField(documentService, "parseTimeoutSeconds", 30);
    }

    @Test
    void shouldSupportPdf() {
        assertTrue(documentService.isSupported("report.pdf"));
    }

    @Test
    void shouldSupportTxt() {
        assertTrue(documentService.isSupported("notes.txt"));
    }

    @Test
    void shouldSupportUpperCaseExtension() {
        assertTrue(documentService.isSupported("FILE.PDF"));
        assertTrue(documentService.isSupported("FILE.TXT"));
    }

    @Test
    void shouldRejectUnsupportedTypes() {
        assertFalse(documentService.isSupported("image.png"));
        assertFalse(documentService.isSupported("code.java"));
        assertFalse(documentService.isSupported("archive.zip"));
        assertFalse(documentService.isSupported("script.exe"));
    }

    @Test
    void shouldRejectNullFileName() {
        assertFalse(documentService.isSupported(null));
    }

    @Test
    void shouldRejectFileWithoutExtension() {
        assertFalse(documentService.isSupported("noextension"));
    }

    @Test
    void shouldGetExtensionCorrectly() {
        assertEquals("pdf", documentService.getExtension("report.pdf"));
        assertEquals("txt", documentService.getExtension("my.notes.txt"));
        assertEquals("pdf", documentService.getExtension("FILE.PDF"));
        assertEquals("", documentService.getExtension("noext"));
        assertEquals("", documentService.getExtension(null));
    }

    @Test
    void shouldRejectNonHttpsUrl() {
        DocumentExtractionResult result = documentService.extractText("http://example.com/file.pdf", "file.pdf");
        assertEquals(DocumentExtractionResult.Status.INVALID_SOURCE, result.status());
    }

    @Test
    void shouldRejectNonTelegramUrl() {
        DocumentExtractionResult result = documentService.extractText("https://evil.com/file.pdf", "file.pdf");
        assertEquals(DocumentExtractionResult.Status.INVALID_SOURCE, result.status());
    }

    @Test
    void shouldRejectTelegramLookalikeSubdomain() {
        DocumentExtractionResult result = documentService.extractText("https://cdn.api.telegram.org.evil.com/file.pdf", "file.pdf");
        assertEquals(DocumentExtractionResult.Status.INVALID_SOURCE, result.status());
    }

    @Test
    void shouldRejectUnsupportedExtensionInExtract() {
        DocumentExtractionResult result = documentService.extractText("https://api.telegram.org/file/bot/test.docx", "test.docx");
        assertEquals(DocumentExtractionResult.Status.UNSUPPORTED_TYPE, result.status());
    }

    @Test
    void shouldRejectInvalidUrl() {
        DocumentExtractionResult result = documentService.extractText("not-a-url", "test.txt");
        assertEquals(DocumentExtractionResult.Status.INVALID_SOURCE, result.status());
    }

    @Test
    void shouldHandleMultipleDotsInFileName() {
        assertTrue(documentService.isSupported("my.report.2024.pdf"));
        assertEquals("pdf", documentService.getExtension("my.report.2024.pdf"));
    }
}
