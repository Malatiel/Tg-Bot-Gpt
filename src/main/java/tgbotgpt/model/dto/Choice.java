package tgbotgpt.model.dto;

import com.fasterxml.jackson.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

import javax.annotation.processing.Generated;
import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "message",
        "finish_reason",
        "index"
})
@Generated("jsonschema2pojo")
@Data
@Accessors(chain = true)
public class Choice {

    @JsonProperty("message")
    private Message message;
    @JsonProperty("finish_reason")
    private String finishReason;
    @JsonProperty("index")
    private Integer index;
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
}
