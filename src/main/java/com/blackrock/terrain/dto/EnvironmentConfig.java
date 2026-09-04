package com.blackrock.terrain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnvironmentConfig {
    private String environment;
    private Boolean preprod;
    private Boolean prod;

    @JsonProperty("is_production")
    private Boolean isProduction;

    @JsonProperty("tf_state_storage_account_name")
    private String tfStateStorageAccountName;

    @JsonProperty("tf_state_subscription_id")
    private String tfStateSubscriptionId;

    @JsonProperty("version_azurerm")
    private String versionAzurerm;

    @JsonProperty("peer_to_blk")
    private Boolean peerToBlk;
}
