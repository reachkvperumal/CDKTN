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
public class StorageAccountDto {
    private String id;
    private String tribe;
    private String redundancy;
    private String performance;

    @JsonProperty("access_tier")
    private String accessTier;

    @JsonProperty("tenant_name")
    private String tenantName;

    @JsonProperty("enable_replication")
    private Boolean enableReplication;

    @JsonProperty("expc_GB_storage")
    private Integer expcGbStorage;

    @JsonProperty("soft_delete_duration")
    private Integer softDeleteDuration;

    @JsonProperty("permanent_delete_enabled")
    private Boolean permanentDeleteEnabled;

    @JsonProperty("disable_soft_delete")
    private Boolean disableSoftDelete;

    @JsonProperty("versioning_enabled")
    private Boolean versioningEnabled;

    @JsonProperty("change_feed_enabled")
    private Boolean changeFeedEnabled;

    @JsonProperty("change_feed_retention_days")
    private Integer changeFeedRetentionDays;

    @JsonProperty("azure_data_lake_storage_enabled")
    private Boolean azureDataLakeStorageEnabled;

    @JsonProperty("public_network_access_enabled")
    private Boolean publicNetworkAccessEnabled;

    @JsonProperty("enable_backup_restore")
    private Boolean enableBackupRestore;

    @JsonProperty("enable_microsoft_defender")
    private Boolean enableMicrosoftDefender;

    @JsonProperty("snowflake_environments")
    @Builder.Default
    private List<String> snowflakeEnvironments = new ArrayList<>();

    @JsonProperty("terraform_modules_version")
    private String terraformModulesVersion;

    @JsonProperty("azure_data_lake_storage_properties")
    @Builder.Default
    private Map<String, Object> azureDataLakeStorageProperties = new HashMap<>();

    @Builder.Default
    private Map<String, String> tags = new HashMap<>();

    @JsonProperty("storage_account_releasers")
    @Builder.Default
    private List<String> storageAccountReleasers = new ArrayList<>();

    @JsonProperty("storage_account_owners")
    @Builder.Default
    private List<String> storageAccountOwners = new ArrayList<>();

    private AccessControlDto readers;
    private AccessControlDto writers;

    @Builder.Default
    private Map<String, ContainerDto> containers = new HashMap<>();

    private BudgetDto budgets;

    @JsonProperty("env_overrides")
    @Builder.Default
    private Map<String, Map<String, Object>> envOverrides = new HashMap<>();
}
