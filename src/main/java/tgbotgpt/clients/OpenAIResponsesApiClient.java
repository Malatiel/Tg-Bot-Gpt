package tgbotgpt.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tgbotgpt.model.dto.Choice;
import tgbotgpt.model.dto.Message;
import tgbotgpt.model.dto.request.ChatRequest;
import tgbotgpt.model.dto.response.*;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

@Service
public class OpenAIResponsesApiClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    @Value("${openai.apikey}")
    private String apiKey;

    @Value("${openai.responses.url:https://api.openai.com/v1/responses}")
    private String url;

    @Value("${openai.request.timeout.seconds:30}")
    private long timeoutSeconds;

    @Value("${openai.retry.max-attempts:2}")
    private long retryMaxAttempts;

    @Value("${openai.retry.delay.ms:500}")
    private long retryDelayMs;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper mapper;

    public OpenAIResponsesApiClient(WebClient.Builder webClientBuilder, ObjectMapper mapper) {
        this.webClientBuilder = webClientBuilder;
        this.mapper = mapper;
    }

    public Mono<ChatResponse> getCompletion(ChatRequest chatRequest) {
        return Mono.defer(() -> webClient().post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(toResponsesRequest(chatRequest, false))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, response -> mapError(response.statusCode(), response.bodyToMono(String.class)))
                        .bodyToMono(ResponsesResponse.class)
                        .map(this::toChatResponse))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .onErrorMap(this::mapTransportError)
                .retryWhen(retrySpec());
    }

    public Flux<StreamChunk> getCompletionStream(ChatRequest chatRequest) {
        return Flux.defer(() -> webClient().post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .bodyValue(toResponsesRequest(chatRequest, true))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, response -> mapError(response.statusCode(), response.bodyToMono(String.class)))
                        .bodyToFlux(SSE_TYPE)
                        .filter(event -> event.data() != null && !"[DONE]".equals(event.data()))
                        .map(event -> parseStreamEvent(event.data()))
                        .filter(this::isTextDeltaEvent)
                        .map(this::streamDelta)
                        .filter(delta -> delta != null && !delta.isEmpty())
                        .map(this::toStreamChunk))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .onErrorMap(this::mapTransportError)
                .retryWhen(retrySpec());
    }

    private WebClient webClient() {
        return webClientBuilder
                .baseUrl(url)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    private Map<String, Object> toResponsesRequest(ChatRequest chatRequest, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", chatRequest.getModel());
        if (chatRequest.getTemperature() != null) {
            body.put("temperature", chatRequest.getTemperature());
        }
        if (chatRequest.getMaxTokens() != null) {
            body.put("max_output_tokens", chatRequest.getMaxTokens());
        }
        if (stream) {
            body.put("stream", true);
        }

        List<Object> input = new ArrayList<>();
        StringBuilder instructions = new StringBuilder();
        if (chatRequest.getMessages() != null) {
            for (Message message : chatRequest.getMessages()) {
                if ("system".equals(message.getRole())) {
                    if (!instructions.isEmpty()) {
                        instructions.append("\n\n");
                    }
                    instructions.append(message.getContentAsString());
                } else {
                    input.add(toInputMessage(message));
                }
            }
        }
        if (!instructions.isEmpty()) {
            body.put("instructions", instructions.toString());
        }
        if (input.isEmpty()) {
            throw new OpenAiClientException("Responses API request requires at least one non-system message", false);
        }
        body.put("input", input);
        return body;
    }

    private Map<String, Object> toInputMessage(Message message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "message");
        item.put("role", normalizeRole(message.getRole()));
        item.put("content", toInputContent(message.getContent()));
        return item;
    }

    private String normalizeRole(String role) {
        if ("assistant".equals(role)) {
            return "assistant";
        }
        return "user";
    }

    private Object toInputContent(Object content) {
        if (content instanceof List<?> parts) {
            return parts.stream()
                    .filter(Map.class::isInstance)
                    .map(part -> toInputPart((Map<?, ?>) part))
                    .filter(Objects::nonNull)
                    .toList();
        }
        return content != null ? content.toString() : "";
    }

    private Map<String, Object> toInputPart(Map<?, ?> part) {
        Object type = part.get("type");
        if ("text".equals(type)) {
            return Map.of(
                    "type", "input_text",
                    "text", Objects.toString(part.get("text"), "")
            );
        }
        if ("image_url".equals(type) && part.get("image_url") instanceof Map<?, ?> imageUrl) {
            return Map.of(
                    "type", "input_image",
                    "image_url", Objects.toString(imageUrl.get("url"), ""),
                    "detail", Objects.toString(imageUrl.get("detail"), "auto")
            );
        }
        return null;
    }

    private ChatResponse toChatResponse(ResponsesResponse response) {
        ChatResponse chatResponse = new ChatResponse();
        chatResponse.setId(response.getId());
        chatResponse.setModel(response.getModel());
        chatResponse.setUsage(response.getUsage());

        Message message = new Message();
        message.setRole("assistant");
        message.setContent(extractText(response));

        Choice choice = new Choice();
        choice.setMessage(message);
        chatResponse.setChoices(List.of(choice));
        return chatResponse;
    }

    private String extractText(ResponsesResponse response) {
        if (response.getOutputText() != null && !response.getOutputText().isBlank()) {
            return response.getOutputText();
        }
        if (response.getOutput() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ResponsesOutputItem item : response.getOutput()) {
            if (item.getContent() == null) continue;
            for (ResponsesContentPart part : item.getContent()) {
                if (part.getText() != null) {
                    text.append(part.getText());
                }
            }
        }
        return text.toString();
    }

    private ResponsesStreamEvent parseStreamEvent(String data) {
        try {
            return mapper.readValue(data, ResponsesStreamEvent.class);
        } catch (IOException e) {
            throw new OpenAiClientException("Failed to parse responses stream event", e, false);
        }
    }

    private String streamDelta(ResponsesStreamEvent event) {
        return event.getDelta();
    }

    private boolean isTextDeltaEvent(ResponsesStreamEvent event) {
        return "response.output_text.delta".equals(event.getType());
    }

    private StreamChunk toStreamChunk(String delta) {
        Message message = new Message();
        message.setContent(delta);

        StreamChoice choice = new StreamChoice();
        choice.setDelta(message);

        StreamChunk chunk = new StreamChunk();
        chunk.setChoices(List.of(choice));
        return chunk;
    }

    private Mono<? extends Throwable> mapError(HttpStatusCode statusCode, Mono<String> bodyMono) {
        return bodyMono.defaultIfEmpty("")
                .map(body -> new OpenAiClientException(buildErrorMessage(statusCode, body), isRetryableStatus(statusCode.value())));
    }

    private String buildErrorMessage(HttpStatusCode statusCode, String body) {
        String normalizedBody = body == null ? "" : body.trim();
        if (normalizedBody.isEmpty()) {
            return "OpenAI Responses API request failed with status " + statusCode.value();
        }
        return "OpenAI Responses API request failed with status " + statusCode.value() + ": " + normalizedBody;
    }

    private Throwable mapTransportError(Throwable error) {
        if (error instanceof OpenAiClientException) {
            return error;
        }
        return new OpenAiClientException("OpenAI Responses API request failed", error, true);
    }

    private Retry retrySpec() {
        return Retry.backoff(retryMaxAttempts, Duration.ofMillis(retryDelayMs))
                .filter(this::isRetryable)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private boolean isRetryable(Throwable error) {
        return error instanceof OpenAiClientException exception && exception.isRetryable();
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }
}
