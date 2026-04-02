package tgbotgpt.clients;

public class OpenAiClientException extends RuntimeException {

    private final boolean retryable;

    public OpenAiClientException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public OpenAiClientException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
