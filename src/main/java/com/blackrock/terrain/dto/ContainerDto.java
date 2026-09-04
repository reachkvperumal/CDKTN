package com.blackrock.terrain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ContainerDto {
    @JsonProperty("container_owners")
    @Builder.Default
    private List<String> containerOwners = new ArrayList<>();

    private String replication;

    @Builder.Default
    private List<String> environments = new ArrayList<>();

    @JsonProperty("lifecycle_management")
    @Builder.Default
    private Map<String, Object> lifecycleManagement = new HashMap<>();

    @JsonProperty("env_overrides")
    @Builder.Default
    private Map<String, Map<String, Object>> envOverrides = new HashMap<>();
}
