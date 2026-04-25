package tgbotgpt.model.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesStreamEvent {

    @JsonProperty("type")
    private String type;

    @JsonProperty("delta")
    private String delta;
}
