package tgbotgpt.model.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import tgbotgpt.model.dto.Usage;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesResponse {

    @JsonProperty("id")
    private String id;

    @JsonProperty("model")
    private String model;

    @JsonProperty("output_text")
    private String outputText;

    @JsonProperty("output")
    private List<ResponsesOutputItem> output;

    @JsonProperty("usage")
    private Usage usage;

    @JsonProperty("error")
    private ResponsesError error;
}
