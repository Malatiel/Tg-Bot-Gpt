package tgbotgpt.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tgbotgpt.model.dto.request.ChatRequest;
import tgbotgpt.model.dto.response.ChatResponse;
import tgbotgpt.model.dto.response.StreamChunk;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIApiClientTest {

    @Test
    void shouldSendJsonRequestAndParseResponse() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        OpenAIApiClient client = createClient(request -> {
            capturedRequest.set(request);
            return Mono.just(jsonResponse(HttpStatus.OK,
                    """
                    {"id":"1","choices":[{"message":{"role":"assistant","content":"Hello"}}],"usage":{"total_tokens":12}}
                    """));
        });

        ChatResponse response = client.getCompletion(chatRequest()).block();

        assertNotNull(response);
        assertEquals("Hello", response.getChoices().get(0).getMessage().getContentAsString());
        assertEquals(12, response.getUsage().getTotalTokens());
        assertEquals(MediaType.APPLICATION_JSON, capturedRequest.get().headers().getContentType());
        assertEquals(List.of("Bearer test-key"), capturedRequest.get().headers().get(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void shouldRetryRetryableErrorsAndSurfaceDetailedMessage() {
        AtomicInteger requestCount = new AtomicInteger();
        OpenAIApiClient client = createClient(request -> {
            requestCount.incrementAndGet();
            return Mono.just(jsonResponse(HttpStatus.TOO_MANY_REQUESTS,
                    """
                    {"error":{"message":"rate limited"}}
                    """));
        });

        OpenAiClientException error = assertThrows(
                OpenAiClientException.class,
                () -> client.getCompletion(chatRequest()).block()
        );

        assertTrue(error.getMessage().contains("429"));
        assertTrue(error.getMessage().contains("rate limited"));
        assertEquals(3, requestCount.get());
    }

    @Test
    void shouldParseStreamingResponse() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        OpenAIApiClient client = createClient(request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body("""
                            data: {"id":"1","choices":[{"delta":{"content":"Hel"}}]}

                            data: {"id":"1","choices":[{"delta":{"content":"lo"}}]}

                            data: [DONE]

                            """)
                    .build());
        });

        StreamChunk first = client.getCompletionStream(chatRequest()).blockFirst();

        assertNotNull(first);
        assertEquals("Hel", first.getChoices().get(0).getDelta().getContentAsString());
        assertEquals(List.of(MediaType.TEXT_EVENT_STREAM), capturedRequest.get().headers().getAccept());
    }

    private OpenAIApiClient createClient(ExchangeFunction exchangeFunction) {
        OpenAIApiClient client = new OpenAIApiClient(
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper()
        );
        org.springframework.test.util.ReflectionTestUtils.setField(client, "apiKey", "test-key");
        org.springframework.test.util.ReflectionTestUtils.setField(client, "url", "http://localhost/v1/chat/completions");
        org.springframework.test.util.ReflectionTestUtils.setField(client, "timeoutSeconds", 5L);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "retryMaxAttempts", 2L);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "retryDelayMs", 1L);
        return client;
    }

    private ChatRequest chatRequest() {
        ChatRequest request = new ChatRequest();
        request.setModel("gpt-4o-mini");
        request.setMessages(List.of());
        return request;
    }

    private ClientResponse jsonResponse(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
