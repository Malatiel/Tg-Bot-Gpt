package tgbotgpt.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ImageServiceTest {

    private ImageService imageService;

    @BeforeEach
    void setUp() {
        imageService = new ImageService();
        ReflectionTestUtils.setField(imageService, "maxSizeMb", 10);
        ReflectionTestUtils.setField(imageService, "allowedTypes", Set.of("image/jpeg", "image/png", "image/gif", "image/webp"));
    }

    @Test
    void shouldAllowJpegType() {
        assertTrue(imageService.isAllowedType("image/jpeg"));
    }

    @Test
    void shouldAllowPngType() {
        assertTrue(imageService.isAllowedType("image/png"));
    }

    @Test
    void shouldRejectSvgType() {
        assertFalse(imageService.isAllowedType("image/svg+xml"));
    }

    @Test
    void shouldRejectNullType() {
        assertFalse(imageService.isAllowedType(null));
    }

    @Test
    void shouldRejectTextType() {
        assertFalse(imageService.isAllowedType("text/html"));
    }

    @Test
    void shouldGuessMimeTypeFromPath() {
        assertEquals("image/png", imageService.guessMimeType("photo.png"));
        assertEquals("image/gif", imageService.guessMimeType("anim.gif"));
        assertEquals("image/webp", imageService.guessMimeType("photo.webp"));
        assertEquals("image/jpeg", imageService.guessMimeType("photo.jpg"));
        assertEquals("image/jpeg", imageService.guessMimeType(null));
    }

    @Test
    void shouldRejectNonHttpsUrl() {
        ImageDownloadResult result = imageService.downloadAndEncode("http://example.com/image.jpg", "image/jpeg");
        assertEquals(ImageDownloadResult.Status.INVALID_SOURCE, result.status());
    }

    @Test
    void shouldRejectNonTelegramUrl() {
        ImageDownloadResult result = imageService.downloadAndEncode("https://evil.com/image.jpg", "image/jpeg");
        assertEquals(ImageDownloadResult.Status.INVALID_SOURCE, result.status());
    }

    @Test
    void shouldRejectDisallowedMimeType() {
        ImageDownloadResult result = imageService.downloadAndEncode("https://api.telegram.org/file/bot/test.svg", "image/svg+xml");
        assertEquals(ImageDownloadResult.Status.UNSUPPORTED_TYPE, result.status());
    }

    @Test
    void shouldGuessMimeTypeCaseInsensitive() {
        assertEquals("image/png", imageService.guessMimeType("PHOTO.PNG"));
        assertEquals("image/gif", imageService.guessMimeType("ANIM.GIF"));
        assertEquals("image/webp", imageService.guessMimeType("file.WEBP"));
    }

    @Test
    void shouldGuessMimeTypeWithMultipleDots() {
        assertEquals("image/png", imageService.guessMimeType("photo.backup.png"));
        assertEquals("image/jpeg", imageService.guessMimeType("my.file.name.jpg"));
    }

    @Test
    void shouldDefaultToJpegForUnknownExtension() {
        assertEquals("image/jpeg", imageService.guessMimeType("photo.bmp"));
        assertEquals("image/jpeg", imageService.guessMimeType("noextension"));
    }

    @Test
    void shouldRejectInvalidUrl() {
        ImageDownloadResult result = imageService.downloadAndEncode("not-a-url", "image/jpeg");
        assertEquals(ImageDownloadResult.Status.INVALID_SOURCE, result.status());
    }

    @Test
    void shouldAllowAllConfiguredTypes() {
        assertTrue(imageService.isAllowedType("image/jpeg"));
        assertTrue(imageService.isAllowedType("image/png"));
        assertTrue(imageService.isAllowedType("image/gif"));
        assertTrue(imageService.isAllowedType("image/webp"));
    }

    @Test
    void shouldBeCaseInsensitiveOnMimeType() {
        assertTrue(imageService.isAllowedType("IMAGE/JPEG"));
        assertTrue(imageService.isAllowedType("Image/Png"));
    }
}
