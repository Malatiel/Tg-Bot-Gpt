package tgbotgpt.model.dto;

import com.fasterxml.jackson.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Accessors(chain = true)
public class Message {

    @JsonProperty("role")
    private String role;

    /**
     * Can be a String (text) or a List (multimodal content parts for vision).
     * When sending images, content is a list of objects like:
     * [{"type": "text", "text": "..."}, {"type": "image_url", "image_url": {"url": "data:..."}}]
     */
    @JsonProperty("content")
    private Object content;

    @JsonIgnore
    private Map<String, Object> additionalProperties = new HashMap<>();

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String name, Object value) {
        additionalProperties.put(name, value);
    }

    /**
     * Convenience: get content as string (for text-only messages).
     */
    @JsonIgnore
    public String getContentAsString() {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                    sb.append(map.get("text"));
                }
            }
            return sb.toString();
        }
        return content != null ? content.toString() : "";
    }

    /**
     * Create a vision message with text + base64-encoded image.
     */
    public static Message ofVision(String text, String base64Image, String mimeType) {
        Message msg = new Message();
        msg.setRole("user");
        msg.setContent(List.of(
                Map.of("type", "text", "text", text != null ? text : "What's in this image?"),
                Map.of("type", "image_url", "image_url",
                        Map.of("url", "data:" + mimeType + ";base64," + base64Image))
        ));
        return msg;
    }
}
