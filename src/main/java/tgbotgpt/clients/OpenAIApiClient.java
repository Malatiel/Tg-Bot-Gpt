/**
 * Client that interacts with the OpenAI API to get chat completions.
 */
package tgbotgpt.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tgbotgpt.model.dto.request.ChatRequest;
import tgbotgpt.model.dto.response.ChatResponse;

@Service
public class OpenAIApiClient {

    @Value("${openai.apikey}")
    private String apiKey;
    @Value("${openai.url}")
    private String url;
    private WebClient webClient;

    /**
     * Initializes the WebClient with base URL and authorization header.
     */
    @PostConstruct
    private void init() {
        this.webClient = WebClient.builder()
                .baseUrl(url)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    /**
     * Sends a chat request to the OpenAI API and returns a reactive Mono response.
     *
     * @param chatRequest the chat request to send.
     * @return a Mono containing the chat response from OpenAI API.
     */
    public Mono<ChatResponse> getCompletion(ChatRequest chatRequest) {
        ObjectMapper mapper = new ObjectMapper();
        String requestBody;
        try {
            requestBody = mapper.writeValueAsString(chatRequest);
        } catch (Exception e) {
            return Mono.error(new RuntimeException("Failed to serialize request", e));
        }

        return webClient.post()
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        clientResponse -> Mono.error(new RuntimeException("Unexpected status code: " + clientResponse.statusCode()))
                )
                .bodyToMono(ChatResponse.class);
    }
}