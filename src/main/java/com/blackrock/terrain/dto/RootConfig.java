package com.blackrock.terrain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
public class RootConfig {
    @Builder.Default
    private Map<String, Object> defaults = new HashMap<>();

    @Builder.Default
    private List<Map<String, EnvironmentConfig>> environments = new ArrayList<>();

    @JsonProperty("storage_accounts")
    @Builder.Default
    private Map<String, StorageAccountDto> storageAccounts = new HashMap<>();
}
