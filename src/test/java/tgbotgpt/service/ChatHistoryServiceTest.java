package tgbotgpt.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tgbotgpt.model.entity.ChatMessage;
import tgbotgpt.repository.ChatMessageRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import(ChatHistoryService.class)
@ActiveProfiles("test")
class ChatHistoryServiceTest {

    @Autowired
    private ChatHistoryService chatHistoryService;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Test
    void shouldSaveAndRetrieveMessages() {
        chatHistoryService.saveMessage(1L, "user", "Hello", null);
        chatHistoryService.saveMessage(1L, "assistant", "Hi!", 10);

        List<ChatMessage> messages = chatHistoryService.getRecentMessages(1L, 20);
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("Hello", messages.get(0).getContent());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals(10, messages.get(1).getTokensUsed());
    }

    @Test
    void shouldReturnMessagesInChronologicalOrder() {
        for (int i = 0; i < 5; i++) {
            chatHistoryService.saveMessage(1L, "user", "Message " + i, null);
        }

        List<ChatMessage> messages = chatHistoryService.getRecentMessages(1L, 20);
        assertEquals(5, messages.size());
        assertEquals("Message 0", messages.get(0).getContent());
        assertEquals("Message 4", messages.get(4).getContent());
    }

    @Test
    void shouldClearHistory() {
        chatHistoryService.saveMessage(1L, "user", "Test", null);
        chatHistoryService.clearHistory(1L);

        List<ChatMessage> messages = chatHistoryService.getRecentMessages(1L, 20);
        assertTrue(messages.isEmpty());
    }

    @Test
    void shouldIsolateUserHistories() {
        chatHistoryService.saveMessage(1L, "user", "User 1 msg", null);
        chatHistoryService.saveMessage(2L, "user", "User 2 msg", null);

        assertEquals(1, chatHistoryService.getRecentMessages(1L, 20).size());
        assertEquals(1, chatHistoryService.getRecentMessages(2L, 20).size());

        chatHistoryService.clearHistory(1L);
        assertEquals(0, chatHistoryService.getRecentMessages(1L, 20).size());
        assertEquals(1, chatHistoryService.getRecentMessages(2L, 20).size());
    }

    @Test
    void shouldLimitTo20Messages() {
        for (int i = 0; i < 25; i++) {
            chatHistoryService.saveMessage(1L, "user", "Msg " + i, null);
        }

        List<ChatMessage> messages = chatHistoryService.getRecentMessages(1L, 20);
        assertEquals(20, messages.size());
    }

    @Test
    void shouldRespectCustomLimit() {
        for (int i = 0; i < 10; i++) {
            chatHistoryService.saveMessage(1L, "user", "Msg " + i, null);
        }

        List<ChatMessage> messages = chatHistoryService.getRecentMessages(1L, 3);
        assertEquals(3, messages.size());
        // Should return the 3 most recent in chronological order
        assertEquals("Msg 7", messages.get(0).getContent());
        assertEquals("Msg 8", messages.get(1).getContent());
        assertEquals("Msg 9", messages.get(2).getContent());
    }

    @Test
    void shouldReturnEmptyForUnknownUser() {
        List<ChatMessage> messages = chatHistoryService.getRecentMessages(999L, 20);
        assertTrue(messages.isEmpty());
    }

    @Test
    void shouldSaveMessageWithNullTokens() {
        chatHistoryService.saveMessage(1L, "user", "Hello", null);

        List<ChatMessage> messages = chatHistoryService.getRecentMessages(1L, 20);
        assertEquals(1, messages.size());
        assertNull(messages.get(0).getTokensUsed());
    }

    @Test
    void shouldPreserveRolesCorrectly() {
        chatHistoryService.saveMessage(1L, "user", "Question", null);
        chatHistoryService.saveMessage(1L, "assistant", "Answer", 15);
        chatHistoryService.saveMessage(1L, "user", "Follow-up", null);

        List<ChatMessage> messages = chatHistoryService.getRecentMessages(1L, 20);
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("user", messages.get(2).getRole());
    }
}
