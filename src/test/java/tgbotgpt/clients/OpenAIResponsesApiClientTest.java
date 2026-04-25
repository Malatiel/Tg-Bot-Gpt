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
import tgbotgpt.model.dto.Message;
import tgbotgpt.model.dto.request.ChatRequest;
import tgbotgpt.model.dto.response.ChatResponse;
import tgbotgpt.model.dto.response.StreamChunk;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIResponsesApiClientTest {

    @Test
    void shouldSendJsonRequestAndParseOutputText() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        OpenAIResponsesApiClient client = createClient(request -> {
            capturedRequest.set(request);
            return Mono.just(jsonResponse(HttpStatus.OK,
                    """
                    {"id":"resp_1","model":"gpt-4o-mini","output_text":"Hello","usage":{"total_tokens":12}}
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
    void shouldParseOutputContentWhenOutputTextIsMissing() {
        OpenAIResponsesApiClient client = createClient(request -> Mono.just(jsonResponse(HttpStatus.OK,
                """
                {
                  "id":"resp_1",
                  "output":[
                    {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hel"},{"type":"output_text","text":"lo"}]}
                  ],
                  "usage":{"total_tokens":7}
                }
                """)));

        ChatResponse response = client.getCompletion(chatRequest()).block();

        assertNotNull(response);
        assertEquals("Hello", response.getChoices().get(0).getMessage().getContentAsString());
        assertEquals(7, response.getUsage().getTotalTokens());
    }

    @Test
    void shouldParseStreamingTextDeltas() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        OpenAIResponsesApiClient client = createClient(request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body("""
                            data: {"type":"response.output_text.delta","delta":"Hel"}

                            data: {"type":"response.output_text.delta","delta":"lo"}

                            data: {"type":"response.completed"}

                            data: [DONE]

                            """)
                    .build());
        });

        List<StreamChunk> chunks = client.getCompletionStream(chatRequest()).collectList().block();

        assertNotNull(chunks);
        assertEquals(2, chunks.size());
        assertEquals("Hel", chunks.get(0).getChoices().get(0).getDelta().getContentAsString());
        assertEquals("lo", chunks.get(1).getChoices().get(0).getDelta().getContentAsString());
        assertEquals(List.of(MediaType.TEXT_EVENT_STREAM), capturedRequest.get().headers().getAccept());
    }

    @Test
    void shouldConvertChatRequestToResponsesRequest() {
        OpenAIResponsesApiClient client = createClient(request -> Mono.just(jsonResponse(HttpStatus.OK, "{}")));
        ChatRequest request = chatRequest();
        Message vision = Message.ofVision("What is this?", "abc123", "image/jpeg");
        request.setMessages(List.of(request.getMessages().get(0), request.getMessages().get(1), vision));

        Map<String, Object> body = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                client,
                "toResponsesRequest",
                request,
                true
        );

        assertNotNull(body);
        assertEquals("gpt-4o-mini", body.get("model"));
        assertEquals("You are helpful.", body.get("instructions"));
        assertEquals(100, body.get("max_output_tokens"));
        assertEquals(true, body.get("stream"));

        List<?> input = assertInstanceOf(List.class, body.get("input"));
        Map<?, ?> textMessage = assertInstanceOf(Map.class, input.get(0));
        assertEquals("message", textMessage.get("type"));
        assertEquals("user", textMessage.get("role"));
        assertEquals("Hello", textMessage.get("content"));

        Map<?, ?> visionMessage = assertInstanceOf(Map.class, input.get(1));
        List<?> visionContent = assertInstanceOf(List.class, visionMessage.get("content"));
        assertEquals(Map.of("type", "input_text", "text", "What is this?"), visionContent.get(0));
        assertEquals(
                Map.of("type", "input_image", "image_url", "data:image/jpeg;base64,abc123", "detail", "auto"),
                visionContent.get(1)
        );
    }

    @Test
    void shouldRejectResponsesRequestWithoutUserInput() {
        OpenAIResponsesApiClient client = createClient(request -> fail("HTTP call should not be attempted"));
        Message system = new Message();
        system.setRole("system");
        system.setContent("You are helpful.");
        ChatRequest request = chatRequest();
        request.setMessages(List.of(system));

        OpenAiClientException error = assertThrows(OpenAiClientException.class,
                () -> client.getCompletion(request).block());

        assertFalse(error.isRetryable());
        assertTrue(error.getMessage().contains("requires at least one non-system message"));
    }

    private OpenAIResponsesApiClient createClient(ExchangeFunction exchangeFunction) {
        OpenAIResponsesApiClient client = new OpenAIResponsesApiClient(
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper()
        );
        org.springframework.test.util.ReflectionTestUtils.setField(client, "apiKey", "test-key");
        org.springframework.test.util.ReflectionTestUtils.setField(client, "url", "http://localhost/v1/responses");
        org.springframework.test.util.ReflectionTestUtils.setField(client, "timeoutSeconds", 5L);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "retryMaxAttempts", 2L);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "retryDelayMs", 1L);
        return client;
    }

    private ChatRequest chatRequest() {
        Message system = new Message();
        system.setRole("system");
        system.setContent("You are helpful.");

        Message user = new Message();
        user.setRole("user");
        user.setContent("Hello");

        ChatRequest request = new ChatRequest();
        request.setModel("gpt-4o-mini");
        request.setTemperature(0.7);
        request.setMaxTokens(100);
        request.setMessages(List.of(system, user));
        return request;
    }

    private ClientResponse jsonResponse(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
