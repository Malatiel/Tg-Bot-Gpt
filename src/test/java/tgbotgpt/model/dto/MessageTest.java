package tgbotgpt.model.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    void shouldReturnStringContentDirectly() {
        Message msg = new Message();
        msg.setContent("Hello");

        assertEquals("Hello", msg.getContentAsString());
    }

    @Test
    void shouldReturnEmptyStringForNullContent() {
        Message msg = new Message();
        msg.setContent(null);

        assertEquals("", msg.getContentAsString());
    }

    @Test
    void shouldExtractTextFromListContent() {
        Message msg = new Message();
        msg.setContent(List.of(
                Map.of("type", "text", "text", "Hello "),
                Map.of("type", "image_url", "image_url", "data:..."),
                Map.of("type", "text", "text", "World")
        ));

        assertEquals("Hello World", msg.getContentAsString());
    }

    @Test
    void shouldReturnEmptyForListWithNoTextParts() {
        Message msg = new Message();
        msg.setContent(List.of(
                Map.of("type", "image_url", "image_url", "data:...")
        ));

        assertEquals("", msg.getContentAsString());
    }

    @Test
    void shouldHandleNonStringNonListContent() {
        Message msg = new Message();
        msg.setContent(42);

        assertEquals("42", msg.getContentAsString());
    }

    @Test
    void shouldCreateVisionMessageWithText() {
        Message msg = Message.ofVision("Describe this", "base64data", "image/png");

        assertEquals("user", msg.getRole());
        assertTrue(msg.getContent() instanceof List);
        String textContent = msg.getContentAsString();
        assertEquals("Describe this", textContent);
    }

    @Test
    void shouldCreateVisionMessageWithNullText() {
        Message msg = Message.ofVision(null, "base64data", "image/jpeg");

        assertEquals("user", msg.getRole());
        assertEquals("What's in this image?", msg.getContentAsString());
    }

    @Test
    void shouldHandleEmptyListContent() {
        Message msg = new Message();
        msg.setContent(List.of());

        assertEquals("", msg.getContentAsString());
    }
}
