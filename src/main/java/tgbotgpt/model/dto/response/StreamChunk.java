package tgbotgpt.model.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import tgbotgpt.model.dto.Usage;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StreamChunk {

    @JsonProperty("id")
    private String id;

    @JsonProperty("choices")
    private List<StreamChoice> choices;

    @JsonProperty("usage")
    private Usage usage;
}
