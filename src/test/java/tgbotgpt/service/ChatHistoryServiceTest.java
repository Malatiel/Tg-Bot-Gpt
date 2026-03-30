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

        List<ChatMessage> messages = chatHistoryService.getRecentMessages(1L);
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

        List<ChatMessage> messages = chatHistoryService.getRecentMessages(1L);
        assertEquals(5, messages.size());
        assertEquals("Message 0", messages.get(0).getContent());
        assertEquals("Message 4", messages.get(4).getContent());
    }

    @Test
    void shouldClearHistory() {
        chatHistoryService.saveMessage(1L, "user", "Test", null);
        chatHistoryService.clearHistory(1L);

        List<ChatMessage> messages = chatHistoryService.getRecentMessages(1L);
        assertTrue(messages.isEmpty());
    }

    @Test
    void shouldIsolateUserHistories() {
        chatHistoryService.saveMessage(1L, "user", "User 1 msg", null);
        chatHistoryService.saveMessage(2L, "user", "User 2 msg", null);

        assertEquals(1, chatHistoryService.getRecentMessages(1L).size());
        assertEquals(1, chatHistoryService.getRecentMessages(2L).size());

        chatHistoryService.clearHistory(1L);
        assertEquals(0, chatHistoryService.getRecentMessages(1L).size());
        assertEquals(1, chatHistoryService.getRecentMessages(2L).size());
    }

    @Test
    void shouldLimitTo20Messages() {
        for (int i = 0; i < 25; i++) {
            chatHistoryService.saveMessage(1L, "user", "Msg " + i, null);
        }

        List<ChatMessage> messages = chatHistoryService.getRecentMessages(1L);
        assertEquals(20, messages.size());
    }
}
