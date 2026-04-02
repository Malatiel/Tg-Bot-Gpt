package tgbotgpt.service;

public record DocumentExtractionResult(Status status, String text) {

    public enum Status {
        SUCCESS,
        UNSUPPORTED_TYPE,
        INVALID_SOURCE,
        TOO_LARGE,
        TIMEOUT,
        UNREADABLE
    }

    public static DocumentExtractionResult success(String text) {
        return new DocumentExtractionResult(Status.SUCCESS, text);
    }

    public static DocumentExtractionResult unsupportedType() {
        return new DocumentExtractionResult(Status.UNSUPPORTED_TYPE, null);
    }

    public static DocumentExtractionResult invalidSource() {
        return new DocumentExtractionResult(Status.INVALID_SOURCE, null);
    }

    public static DocumentExtractionResult tooLarge() {
        return new DocumentExtractionResult(Status.TOO_LARGE, null);
    }

    public static DocumentExtractionResult timeout() {
        return new DocumentExtractionResult(Status.TIMEOUT, null);
    }

    public static DocumentExtractionResult unreadable() {
        return new DocumentExtractionResult(Status.UNREADABLE, null);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
