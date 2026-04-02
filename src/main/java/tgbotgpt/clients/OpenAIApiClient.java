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
import tgbotgpt.model.dto.request.ChatRequest;
import tgbotgpt.model.dto.response.ChatResponse;
import tgbotgpt.model.dto.response.StreamChunk;

import java.io.IOException;
import java.time.Duration;

@Service
public class OpenAIApiClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    @Value("${openai.apikey}")
    private String apiKey;

    @Value("${openai.url}")
    private String url;

    @Value("${openai.request.timeout.seconds:30}")
    private long timeoutSeconds;

    @Value("${openai.retry.max-attempts:2}")
    private long retryMaxAttempts;

    @Value("${openai.retry.delay.ms:500}")
    private long retryDelayMs;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper mapper;

    public OpenAIApiClient(WebClient.Builder webClientBuilder, ObjectMapper mapper) {
        this.webClientBuilder = webClientBuilder;
        this.mapper = mapper;
    }

    public Mono<ChatResponse> getCompletion(ChatRequest chatRequest) {
        return webClient().post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequest)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> mapError(response.statusCode(), response.bodyToMono(String.class)))
                .bodyToMono(ChatResponse.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .onErrorMap(this::mapTransportError)
                .retryWhen(retrySpec());
    }

    public Flux<StreamChunk> getCompletionStream(ChatRequest chatRequest) {
        chatRequest.setStream(true);

        return webClient().post()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(chatRequest)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> mapError(response.statusCode(), response.bodyToMono(String.class)))
                .bodyToFlux(SSE_TYPE)
                .filter(event -> event.data() != null && !"[DONE]".equals(event.data()))
                .map(event -> parseChunk(event.data()))
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

    private StreamChunk parseChunk(String data) {
        try {
            return mapper.readValue(data, StreamChunk.class);
        } catch (IOException e) {
            throw new OpenAiClientException("Failed to parse stream chunk", e, false);
        }
    }

    private Mono<? extends Throwable> mapError(HttpStatusCode statusCode, Mono<String> bodyMono) {
        return bodyMono.defaultIfEmpty("")
                .map(body -> new OpenAiClientException(buildErrorMessage(statusCode, body), isRetryableStatus(statusCode.value())));
    }

    private String buildErrorMessage(HttpStatusCode statusCode, String body) {
        String normalizedBody = body == null ? "" : body.trim();
        if (normalizedBody.isEmpty()) {
            return "OpenAI API request failed with status " + statusCode.value();
        }
        return "OpenAI API request failed with status " + statusCode.value() + ": " + normalizedBody;
    }

    private Throwable mapTransportError(Throwable error) {
        if (error instanceof OpenAiClientException) {
            return error;
        }
        return new OpenAiClientException("OpenAI API request failed", error, true);
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
