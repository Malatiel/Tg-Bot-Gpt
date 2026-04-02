package tgbotgpt.service;

public record ImageDownloadResult(Status status, String base64) {

    public enum Status {
        SUCCESS,
        UNSUPPORTED_TYPE,
        INVALID_SOURCE,
        TOO_LARGE,
        UNREADABLE
    }

    public static ImageDownloadResult success(String base64) {
        return new ImageDownloadResult(Status.SUCCESS, base64);
    }

    public static ImageDownloadResult unsupportedType() {
        return new ImageDownloadResult(Status.UNSUPPORTED_TYPE, null);
    }

    public static ImageDownloadResult invalidSource() {
        return new ImageDownloadResult(Status.INVALID_SOURCE, null);
    }

    public static ImageDownloadResult tooLarge() {
        return new ImageDownloadResult(Status.TOO_LARGE, null);
    }

    public static ImageDownloadResult unreadable() {
        return new ImageDownloadResult(Status.UNREADABLE, null);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
