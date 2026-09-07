package com.blackrock.terrain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class EventSubscriptionDto {

    @JsonProperty("event_types")
    @Builder.Default
    private List<String> eventTypes = new ArrayList<>();

    @JsonProperty("endpoint_name")
    private String endpointName;

    @JsonProperty("endpoint_type")
    private String endpointType;

    @JsonProperty("subject_begins_with")
    private String subjectBeginsWith;

    @JsonProperty("subject_ends_with")
    private String subjectEndsWith;

    @JsonProperty("included_event_types")
    @Builder.Default
    private List<String> includedEventTypes = new ArrayList<>();

    private Boolean enabled;
}
