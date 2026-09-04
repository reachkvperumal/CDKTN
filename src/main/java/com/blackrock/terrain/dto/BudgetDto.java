package com.blackrock.terrain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BudgetDto {
    private BigDecimal amount;

    @JsonProperty("budget_granularity")
    private String budgetGranularity;

    @JsonProperty("budget_threshold")
    private Integer budgetThreshold;

    @JsonProperty("budget_enabled")
    private Boolean budgetEnabled;

    @JsonProperty("budget_operator")
    private String budgetOperator;

    @JsonProperty("env_overrides")
    @Builder.Default
    private Map<String, Map<String, Object>> envOverrides = new HashMap<>();
}
