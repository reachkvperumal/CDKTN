package com.blackrock.terrain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
public class AccessControlDto {
    @JsonProperty("service_principal")
    @Builder.Default
    private List<String> servicePrincipal = new ArrayList<>();

    @JsonProperty("groupID")
    @Builder.Default
    private List<String> groupId = new ArrayList<>();
}
