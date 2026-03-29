package tgbotgpt.model.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import tgbotgpt.model.dto.Message;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StreamChoice {

    @JsonProperty("index")
    private Integer index;

    @JsonProperty("delta")
    private Message delta;

    @JsonProperty("finish_reason")
    private String finishReason;
}
