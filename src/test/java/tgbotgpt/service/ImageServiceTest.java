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
        String result = imageService.downloadAndEncode("http://example.com/image.jpg", "image/jpeg");
        assertNull(result);
    }

    @Test
    void shouldRejectNonTelegramUrl() {
        String result = imageService.downloadAndEncode("https://evil.com/image.jpg", "image/jpeg");
        assertNull(result);
    }

    @Test
    void shouldRejectDisallowedMimeType() {
        String result = imageService.downloadAndEncode("https://api.telegram.org/file/bot/test.svg", "image/svg+xml");
        assertNull(result);
    }
}
