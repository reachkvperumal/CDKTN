package com.blackrock.terrain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class QueueDto {

    private AccessControlDto readers;
    private AccessControlDto writers;

    @JsonProperty("retention_days")
    private Integer retentionDays;

    @JsonProperty("real_resource_name")
    private String realResourceName;

    private String description;

    @JsonProperty("env_overrides")
    @Builder.Default
    private Map<String, Map<String, Object>> envOverrides = new HashMap<>();

}
