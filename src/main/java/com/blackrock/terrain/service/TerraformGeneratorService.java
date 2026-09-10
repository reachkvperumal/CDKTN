package com.blackrock.terrain.service;

import com.blackrock.terrain.dto.AccessControlDto;
import com.blackrock.terrain.dto.ContainerDto;
import com.blackrock.terrain.dto.EventSubscriptionDto;
import com.blackrock.terrain.dto.QueueDto;
import com.blackrock.terrain.dto.RootConfig;
import com.blackrock.terrain.dto.StorageAccountDto;
import com.blackrock.terrain.exception.ConfigurationLoadException;
import com.blackrock.terrain.exception.TerraformRepoInitializationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cdktn.cdktn.App;
import io.cdktn.cdktn.AppConfig;
import io.cdktn.cdktn.TerraformResource;
import io.cdktn.cdktn.TerraformResourceConfig;
import io.cdktn.cdktn.TerraformStack;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TerraformGeneratorService {

    private static final String RESOURCE_TYPE_STORAGE_ACCOUNT = "azurerm_storage_account";
    private static final String RESOURCE_TYPE_STORAGE_CONTAINER = "azurerm_storage_container";
    private static final String RESOURCE_TYPE_STORAGE_QUEUE = "azurerm_storage_queue";
    private static final String RESOURCE_TYPE_EVENT_SUBSCRIPTION = "azurerm_eventgrid_event_subscription";
    private static final String RESOURCE_TYPE_ROLE_ASSIGNMENT = "azurerm_role_assignment";

    private static final String ATTR_NAME = "name";
    private static final String ATTR_ACCOUNT_ID = "account_id";
    private static final String ATTR_TRIBE = "tribe";
    private static final String ATTR_ACCOUNT_TIER = "account_tier";
    private static final String ATTR_ACCOUNT_REPLICATION_TYPE = "account_replication_type";
    private static final String ATTR_ACCESS_TIER = "access_tier";
    private static final String ATTR_TAGS = "tags";
    private static final String ATTR_AZURE_DATA_LAKE_STORAGE_PROPERTIES = "azure_data_lake_storage_properties";
    private static final String ATTR_SNOWFLAKE_ENVIRONMENTS = "snowflake_environments";
    private static final String ATTR_STORAGE_ACCOUNT_RELEASERS = "storage_account_releasers";
    private static final String ATTR_STORAGE_ACCOUNT_OWNERS = "storage_account_owners";

    private static final String ATTR_STORAGE_ACCOUNT_NAME = "storage_account_name";
    private static final String ATTR_REPLICATION = "replication";
    private static final String ATTR_CONTAINER_OWNERS = "container_owners";
    private static final String ATTR_ENVIRONMENTS = "environments";
    private static final String ATTR_LIFECYCLE_MANAGEMENT = "lifecycle_management";

    private static final String ATTR_RETENTION_DAYS = "retention_days";
    private static final String ATTR_REAL_RESOURCE_NAME = "real_resource_name";
    private static final String ATTR_DESCRIPTION = "description";
    private static final String ATTR_READERS = "readers";
    private static final String ATTR_WRITERS = "writers";

    private static final String ATTR_EVENT_TYPES = "event_types";
    private static final String ATTR_ENDPOINT_NAME = "endpoint_name";
    private static final String ATTR_ENDPOINT_TYPE = "endpoint_type";
    private static final String ATTR_SUBJECT_BEGINS_WITH = "subject_begins_with";
    private static final String ATTR_SUBJECT_ENDS_WITH = "subject_ends_with";
    private static final String ATTR_INCLUDED_EVENT_TYPES = "included_event_types";
    private static final String ATTR_ENABLED = "enabled";
    private static final String ATTR_DESTROY_SA_ENV = "destroy_sa_env";
    private static final String ATTR_IS_TEST = "is_test";
    private static final String ATTR_SOFT_DELETE_DURATION = "soft_delete_duration";

    private static final String KEY_RESOURCE = "resource";
    private static final String STACKS_DIR = "stacks";
    private static final String CDK_TF_JSON = "cdk.tf.json";
    private static final String EMPTY_JSON = "{}";
    private static final String PREFIX_SA_RESOURCE = "sa_";
    private static final String PREFIX_CONTAINER_RESOURCE = "container_";
    private static final String PREFIX_QUEUE_RESOURCE = "queue_";
    private static final String PREFIX_EVENT_SUB_RESOURCE = "eventsub_";
    private static final String PARTITION_STACK_PREFIX = "PartitionStack_";
    private static final String PARTITION_DIR_PREFIX = "/partition_";

    private final ObjectMapper jsonMapper = new ObjectMapper();

    private static final int PARTITION_SIZE = 500;
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors())
    );

    public String generateTerraformJson(RootConfig rootConfig, String stackName, String outputDirectory) {
        try {
            File outDir = new File(outputDirectory);
            if (!outDir.exists()) {
                outDir.mkdirs();
            }

            App app = new App(AppConfig.builder()
                    .outdir(outDir.getAbsolutePath())
                    .build());

            TerraformStack stack = new TerraformStack(app, stackName);

            if (rootConfig != null && rootConfig.getStorageAccounts() != null) {
                rootConfig.getStorageAccounts().forEach((accountName, accountDto) -> {
                    buildStorageAccountResource(stack, accountName, accountDto);
                });
            }

            app.synth();
            log.info("CDK-Terrain Stack synthesized successfully for stack: {}", stackName);

            return readSynthesizedJson(outDir, stackName);
        } catch (ConfigurationLoadException | TerraformRepoInitializationException e) {
            throw e;
        } catch (Exception e) {
            throw new TerraformRepoInitializationException("Failed to generate Terraform JSON for stack: " + stackName, e);
        }
    }

    /**
     * Synthesizes incoming YAML config into Terraform JSON and upserts/merges it with an existing Terraform JSON.
     */
    public String upsertYamlIntoTerraformJson(RootConfig rootConfig, String existingJson, String stackName, String outputDirectory) {
        try {
            String newlySynthesizedJson = generateTerraformJson(rootConfig, stackName, outputDirectory);
            return upsertTerraformJson(existingJson, newlySynthesizedJson);
        } catch (ConfigurationLoadException | TerraformRepoInitializationException e) {
            throw e;
        } catch (Exception e) {
            throw new TerraformRepoInitializationException("Failed to upsert YAML into Terraform JSON", e);
        }
    }

    /**
     * Merges/upserts newly synthesized Terraform JSON into an existing Terraform JSON structure.
     * Preserves existing non-colliding resources, providers, and backend definitions, while
     * updating matching resource blocks and inserting new ones.
     */
    public String upsertTerraformJson(String existingJson, String newJson) {
        try {
            if (existingJson == null || existingJson.isBlank() || EMPTY_JSON.equals(existingJson.trim())) {
                return newJson;
            }
            if (newJson == null || newJson.isBlank() || EMPTY_JSON.equals(newJson.trim())) {
                return existingJson;
            }

            JsonNode existingTree = jsonMapper.readTree(existingJson);
            JsonNode newTree = jsonMapper.readTree(newJson);

            if (!(existingTree instanceof ObjectNode existingObj) || !(newTree instanceof ObjectNode newObj)) {
                return newJson;
            }

            // Upsert 'resource' block
            if (newObj.has(KEY_RESOURCE) && newObj.get(KEY_RESOURCE).isObject()) {
                ObjectNode newResourceNode = (ObjectNode) newObj.get(KEY_RESOURCE);
                ObjectNode existingResourceNode;
                if (existingObj.has(KEY_RESOURCE) && existingObj.get(KEY_RESOURCE).isObject()) {
                    existingResourceNode = (ObjectNode) existingObj.get(KEY_RESOURCE);
                } else {
                    existingResourceNode = existingObj.putObject(KEY_RESOURCE);
                }

                newResourceNode.fieldNames().forEachRemaining(resourceType -> {
                    JsonNode newTypeBlock = newResourceNode.get(resourceType);
                    if (newTypeBlock.isObject()) {
                        ObjectNode existingTypeBlock;
                        if (existingResourceNode.has(resourceType) && existingResourceNode.get(resourceType).isObject()) {
                            existingTypeBlock = (ObjectNode) existingResourceNode.get(resourceType);
                        } else {
                            existingTypeBlock = existingResourceNode.putObject(resourceType);
                        }

                        ObjectNode newInstances = (ObjectNode) newTypeBlock;
                        newInstances.fieldNames().forEachRemaining(instanceName -> {
                            JsonNode newInstanceValue = newInstances.get(instanceName);
                            if (existingTypeBlock.has(instanceName) && existingTypeBlock.get(instanceName).isObject() && newInstanceValue.isObject()) {
                                deepMerge((ObjectNode) existingTypeBlock.get(instanceName), (ObjectNode) newInstanceValue);
                            } else {
                                existingTypeBlock.set(instanceName, newInstanceValue.deepCopy());
                            }
                        });
                    }
                });
            }

            return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(existingObj);
        } catch (ConfigurationLoadException | TerraformRepoInitializationException e) {
            throw e;
        } catch (Exception e) {
            throw new TerraformRepoInitializationException("Failed to upsert Terraform JSON AST structure", e);
        }
    }

    private void deepMerge(ObjectNode target, ObjectNode source) {
        source.fieldNames().forEachRemaining(fieldName -> {
            JsonNode sourceValue = source.get(fieldName);
            JsonNode targetValue = target.get(fieldName);

            if (targetValue != null && targetValue.isObject() && sourceValue.isObject()) {
                deepMerge((ObjectNode) targetValue, (ObjectNode) sourceValue);
            } else {
                target.set(fieldName, sourceValue.deepCopy());
            }
        });
    }

    /**
     * Optimized parallel partitioned stack generation for large constructs (10K+ lines YAML).
     */
    public List<String> generateLargeScaleTerraformJson(Map<String, StorageAccountDto> allAccounts, String baseOutputDir) {
        if (allAccounts == null || allAccounts.isEmpty()) {
            throw new ConfigurationLoadException("Cannot generate partitioned Terraform JSON: storage accounts map is null or empty");
        }
        try {
            log.info("Starting large-scale multi-partition synthesis for {} storage account resources...", allAccounts.size());
            List<Map.Entry<String, StorageAccountDto>> entries = new ArrayList<>(allAccounts.entrySet());
            List<List<Map.Entry<String, StorageAccountDto>>> partitions = partition(entries, PARTITION_SIZE);

            List<CompletableFuture<String>> futures = new ArrayList<>();

            for (int i = 0; i < partitions.size(); i++) {
                final int partitionIdx = i;
                final List<Map.Entry<String, StorageAccountDto>> chunk = partitions.get(i);

                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    String stackName = PARTITION_STACK_PREFIX + partitionIdx;
                    String partitionOutDirStr = baseOutputDir + PARTITION_DIR_PREFIX + partitionIdx;
                    File outDir = new File(partitionOutDirStr);
                    if (!outDir.exists()) {
                        outDir.mkdirs();
                    }

                    App app = new App(AppConfig.builder().outdir(outDir.getAbsolutePath()).build());
                    TerraformStack stack = new TerraformStack(app, stackName);

                    for (Map.Entry<String, StorageAccountDto> entry : chunk) {
                        buildStorageAccountResource(stack, entry.getKey(), entry.getValue());
                    }

                    app.synth();
                    log.info("Partition stack {} with {} resources synthesized successfully.", stackName, chunk.size());

                    return readSynthesizedJson(outDir, stackName);
                }, executor);

                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<String> results = new ArrayList<>(futures.size());
            for (CompletableFuture<String> future : futures) {
                results.add(future.join());
            }
            return results;
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ConfigurationLoadException cle) {
                throw cle;
            }
            if (cause instanceof TerraformRepoInitializationException trie) {
                throw trie;
            }
            throw new TerraformRepoInitializationException("Failed large-scale multi-partition Terraform JSON synthesis", cause);
        } catch (ConfigurationLoadException | TerraformRepoInitializationException e) {
            throw e;
        } catch (Exception e) {
            throw new TerraformRepoInitializationException("Failed large-scale multi-partition Terraform JSON synthesis", e);
        }
    }





    private void buildStorageAccountResource(TerraformStack stack, String accountName, StorageAccountDto accountDto) {
        if (accountDto == null || accountDto.getId() == null || accountDto.getId().isBlank()) {
            throw new ConfigurationLoadException(
                    "Validation Error: Mandatory attribute 'id' is missing or blank for storage account '" + accountName + "'"
            );
        }

        Map<String, Object> saAttributes = new LinkedHashMap<>();
        saAttributes.put(ATTR_NAME, sanitizeStorageAccountName(accountName));
        saAttributes.put("resource_group_name", accountDto.getTenantName() != null ? "rg-" + accountDto.getTenantName() : "rg-terrain");
        saAttributes.put("location", "eastus");
        saAttributes.put(ATTR_ACCOUNT_TIER, formatAccountTier(accountDto.getPerformance()));
        saAttributes.put(ATTR_ACCOUNT_REPLICATION_TYPE, formatReplicationType(accountDto.getRedundancy()));

        if (accountDto.getAccessTier() != null) {
            saAttributes.put(ATTR_ACCESS_TIER, formatAccountTier(accountDto.getAccessTier()));
        }

        // Pack custom domain metadata into standard 'tags' map
        Map<String, String> tagsMap = new LinkedHashMap<>();
        if (isNonEmpty(accountDto.getTags())) {
            accountDto.getTags().forEach((k, v) -> {
                if (k != null && v != null) {
                    tagsMap.put(k, String.valueOf(v));
                }
            });
        }

        tagsMap.put("account_id", accountDto.getId());
        if (accountDto.getTribe() != null) tagsMap.put("tribe", accountDto.getTribe());

        if (isNonEmpty(accountDto.getStorageAccountReleasers())) {
            tagsMap.put("storage_account_releasers", String.join(",", accountDto.getStorageAccountReleasers()));
        }
        if (isNonEmpty(accountDto.getStorageAccountOwners())) {
            tagsMap.put("storage_account_owners", String.join(",", accountDto.getStorageAccountOwners()));
        }
        if (accountDto.getReaders() != null) {
            tagsMap.put("readers", toJsonString(accountDto.getReaders()));
        }
        if (accountDto.getWriters() != null) {
            tagsMap.put("writers", toJsonString(accountDto.getWriters()));
        }
        if (isNonEmpty(accountDto.getAzureDataLakeStorageProperties())) {
            tagsMap.put("azure_data_lake_storage_properties", toJsonString(accountDto.getAzureDataLakeStorageProperties()));
        }
        if (isNonEmpty(accountDto.getSnowflakeEnvironments())) {
            tagsMap.put("snowflake_environments", toJsonString(accountDto.getSnowflakeEnvironments()));
        }
        if (accountDto.getDescription() != null) {
            tagsMap.put("description", accountDto.getDescription());
        }
        if (accountDto.getIsTest() != null) {
            tagsMap.put("is_test", String.valueOf(accountDto.getIsTest()));
        }
        if (isNonEmpty(accountDto.getDestroySaEnv())) {
            tagsMap.put("destroy_sa_env", toJsonString(accountDto.getDestroySaEnv()));
        }

        if (!tagsMap.isEmpty()) {
            saAttributes.put(ATTR_TAGS, tagsMap);
        }

        String saConstructId = getUniqueConstructId(stack, PREFIX_SA_RESOURCE + accountName);
        TerraformResource saResource = new TerraformResource(stack, saConstructId, TerraformResourceConfig.builder()
                .terraformResourceType(RESOURCE_TYPE_STORAGE_ACCOUNT)
                .build());

        saAttributes.forEach(saResource::addOverride);

        String saScopeRef = "${" + RESOURCE_TYPE_STORAGE_ACCOUNT + "." + saConstructId + ".id}";
        if (accountDto.getReaders() != null) {
            buildRoleAssignments(stack, saScopeRef, accountName, accountDto.getReaders(), "Storage Blob Data Reader");
        }
        if (accountDto.getWriters() != null) {
            buildRoleAssignments(stack, saScopeRef, accountName, accountDto.getWriters(), "Storage Blob Data Contributor");
        }

        if (isNonEmpty(accountDto.getContainers())) {
            accountDto.getContainers().forEach((containerName, containerDto) -> {
                buildContainerResource(stack, accountName, saConstructId, containerName, containerDto);
            });
        }

        if (isNonEmpty(accountDto.getQueues())) {
            accountDto.getQueues().forEach((queueName, queueDto) -> {
                buildQueueResource(stack, accountName, saConstructId, queueName, queueDto);
            });
        }

        if (isNonEmpty(accountDto.getEventSubscriptions())) {
            accountDto.getEventSubscriptions().forEach((subName, subDto) -> {
                buildEventSubscriptionResource(stack, saConstructId, subName, subDto);
            });
        }
    }

    private void buildContainerResource(TerraformStack stack, String saName, String saConstructId, String containerName, ContainerDto containerDto) {
        Map<String, Object> containerAttrs = new LinkedHashMap<>();
        containerAttrs.put(ATTR_NAME, containerName);
        containerAttrs.put("storage_account_id", "${" + RESOURCE_TYPE_STORAGE_ACCOUNT + "." + saConstructId + ".id}");
        containerAttrs.put("container_access_type", "private");

        // Pack custom container domain attributes into valid 'metadata' map
        Map<String, String> metaMap = new LinkedHashMap<>();
        metaMap.put("storage_account_name", saName);
        if (containerDto.getReplication() != null) metaMap.put("replication", containerDto.getReplication());

        if (isNonEmpty(containerDto.getContainerOwners())) {
            metaMap.put("container_owners", toJsonString(containerDto.getContainerOwners()));
        }
        if (isNonEmpty(containerDto.getEnvironments())) {
            metaMap.put("environments", toJsonString(containerDto.getEnvironments()));
        }
        if (isNonEmpty(containerDto.getLifecycleManagement())) {
            metaMap.put("lifecycle_management", toJsonString(containerDto.getLifecycleManagement()));
        }
        if (containerDto.getReaders() != null) {
            metaMap.put("readers", toJsonString(containerDto.getReaders()));
        }
        if (containerDto.getWriters() != null) {
            metaMap.put("writers", toJsonString(containerDto.getWriters()));
        }
        if (containerDto.getRealResourceName() != null) {
            metaMap.put("real_resource_name", containerDto.getRealResourceName());
        }
        if (containerDto.getSoftDeleteDuration() != null) {
            metaMap.put("soft_delete_duration", String.valueOf(containerDto.getSoftDeleteDuration()));
        }
        if (containerDto.getRetentionDays() != null) {
            metaMap.put("retention_days", String.valueOf(containerDto.getRetentionDays()));
        }

        if (!metaMap.isEmpty()) {
            containerAttrs.put("metadata", metaMap);
        }

        String containerConstructId = getUniqueConstructId(stack, PREFIX_CONTAINER_RESOURCE + saName + "_" + containerName);
        TerraformResource containerResource = new TerraformResource(stack, containerConstructId,
                TerraformResourceConfig.builder()
                        .terraformResourceType(RESOURCE_TYPE_STORAGE_CONTAINER)
                        .build());

        containerAttrs.forEach(containerResource::addOverride);

        String containerScopeRef = "${" + RESOURCE_TYPE_STORAGE_CONTAINER + "." + containerConstructId + ".id}";
        if (containerDto.getReaders() != null) {
            buildRoleAssignments(stack, containerScopeRef, saName + "_" + containerName, containerDto.getReaders(), "Storage Blob Data Reader");
        }
        if (containerDto.getWriters() != null) {
            buildRoleAssignments(stack, containerScopeRef, saName + "_" + containerName, containerDto.getWriters(), "Storage Blob Data Contributor");
        }

        if (isNonEmpty(containerDto.getEventSubscriptions())) {
            containerDto.getEventSubscriptions().forEach((subName, subDto) -> {
                buildEventSubscriptionResource(stack, saConstructId, subName, subDto);
            });
        }
    }

    private void buildQueueResource(TerraformStack stack, String saName, String saConstructId, String queueName, QueueDto queueDto) {
        if (queueDto == null) return;

        Map<String, Object> queueAttrs = new LinkedHashMap<>();
        queueAttrs.put(ATTR_NAME, queueName);
        queueAttrs.put("storage_account_id", "${" + RESOURCE_TYPE_STORAGE_ACCOUNT + "." + saConstructId + ".id}");

        Map<String, String> metaMap = new LinkedHashMap<>();
        metaMap.put("storage_account_name", saName);

        if (queueDto.getRealResourceName() != null) {
            metaMap.put("real_resource_name", queueDto.getRealResourceName());
        }
        if (queueDto.getRetentionDays() != null) {
            metaMap.put("retention_days", String.valueOf(queueDto.getRetentionDays()));
        }
        if (queueDto.getDescription() != null) {
            metaMap.put("description", queueDto.getDescription());
        }
        if (queueDto.getReaders() != null) {
            metaMap.put("readers", toJsonString(queueDto.getReaders()));
        }
        if (queueDto.getWriters() != null) {
            metaMap.put("writers", toJsonString(queueDto.getWriters()));
        }

        if (!metaMap.isEmpty()) {
            queueAttrs.put("metadata", metaMap);
        }

        String queueConstructId = getUniqueConstructId(stack, PREFIX_QUEUE_RESOURCE + saName + "_" + queueName);
        TerraformResource queueResource = new TerraformResource(stack, queueConstructId,
                TerraformResourceConfig.builder()
                        .terraformResourceType(RESOURCE_TYPE_STORAGE_QUEUE)
                        .build());

        queueAttrs.forEach(queueResource::addOverride);
    }

    private void buildEventSubscriptionResource(TerraformStack stack, String saConstructId, String subName, EventSubscriptionDto subDto) {
        if (subDto == null) return;

        Map<String, Object> subAttrs = new LinkedHashMap<>();
        subAttrs.put(ATTR_NAME, subName);
        subAttrs.put("scope", "${" + RESOURCE_TYPE_STORAGE_ACCOUNT + "." + saConstructId + ".id}");

        List<String> eventTypes = isNonEmpty(subDto.getIncludedEventTypes()) ? subDto.getIncludedEventTypes()
                : (isNonEmpty(subDto.getEventTypes()) ? subDto.getEventTypes() : null);
        if (isNonEmpty(eventTypes)) {
            subAttrs.put("included_event_types", eventTypes);
        }

        if (subDto.getSubjectBeginsWith() != null || subDto.getSubjectEndsWith() != null) {
            Map<String, Object> subjectFilter = new LinkedHashMap<>();
            if (subDto.getSubjectBeginsWith() != null) {
                subjectFilter.put("subject_begins_with", subDto.getSubjectBeginsWith());
            }
            if (subDto.getSubjectEndsWith() != null) {
                subjectFilter.put("subject_ends_with", subDto.getSubjectEndsWith());
            }
            subAttrs.put("subject_filter", List.of(subjectFilter));
        }

        if (subDto.getEndpointType() != null || subDto.getEndpointName() != null) {
            Map<String, Object> webhookEndpoint = new LinkedHashMap<>();
            String name = subDto.getEndpointName() != null ? subDto.getEndpointName() : "endpoint";
            webhookEndpoint.put("url", "https://example.com/webhooks/" + name);
            subAttrs.put("webhook_endpoint", List.of(webhookEndpoint));
        }

        String eventSubConstructId = getUniqueConstructId(stack, PREFIX_EVENT_SUB_RESOURCE + subName);
        TerraformResource eventSubResource = new TerraformResource(stack, eventSubConstructId,
                TerraformResourceConfig.builder()
                        .terraformResourceType(RESOURCE_TYPE_EVENT_SUBSCRIPTION)
                        .build());

        subAttrs.forEach(eventSubResource::addOverride);
    }

    private void buildRoleAssignments(TerraformStack stack, String scopeReference, String parentName, AccessControlDto accessControl, String roleDefinitionName) {
        if (accessControl == null) return;

        List<String> principals = new ArrayList<>();
        if (isNonEmpty(accessControl.getServicePrincipal())) {
            principals.addAll(accessControl.getServicePrincipal());
        }
        if (isNonEmpty(accessControl.getGroupId())) {
            principals.addAll(accessControl.getGroupId());
        }
        if (isNonEmpty(accessControl.getExternalUuid())) {
            principals.addAll(accessControl.getExternalUuid());
        }

        for (String principal : principals) {
            if (principal == null || principal.isBlank()) continue;

            Map<String, Object> roleAttrs = new LinkedHashMap<>();
            roleAttrs.put("scope", scopeReference);
            roleAttrs.put("role_definition_name", roleDefinitionName);
            roleAttrs.put("principal_id", principal);

            String roleConstructId = getUniqueConstructId(stack, "role_" + parentName + "_" + roleDefinitionName.replaceAll("[^a-zA-Z0-9]", "_") + "_" + principal);
            TerraformResource roleResource = new TerraformResource(stack, roleConstructId,
                    TerraformResourceConfig.builder()
                            .terraformResourceType(RESOURCE_TYPE_ROLE_ASSIGNMENT)
                            .build());

            roleAttrs.forEach(roleResource::addOverride);
        }
    }

    private String formatAccountTier(String tier) {
        if (tier == null || tier.isBlank()) return "Standard";
        String lower = tier.toLowerCase();
        if ("premium".equals(lower)) return "Premium";
        if ("standard".equals(lower)) return "Standard";
        if ("hot".equals(lower)) return "Hot";
        if ("cool".equals(lower)) return "Cool";
        return tier.substring(0, 1).toUpperCase() + tier.substring(1);
    }

    private String formatReplicationType(String redundancy) {
        if (redundancy == null || redundancy.isBlank()) return "LRS";
        return redundancy.toUpperCase();
    }

    private String sanitizeStorageAccountName(String rawName) {
        if (rawName == null) return "sadefault123";
        String sanitized = rawName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        if (sanitized.length() < 3) {
            sanitized = sanitized + "sa123";
        }
        if (sanitized.length() > 24) {
            sanitized = sanitized.substring(0, 24);
        }
        return sanitized;
    }

    private String toJsonString(Object obj) {
        if (obj == null) return "";
        try {
            return jsonMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private boolean isNonEmpty(Collection<?> col) {
        return col != null && !col.isEmpty();
    }

    private boolean isNonEmpty(Map<?, ?> map) {
        return map != null && !map.isEmpty();
    }

    private String getUniqueConstructId(TerraformStack stack, String rawName) {
        String baseId = rawName.replaceAll("[^a-zA-Z0-9_]", "_");
        String uniqueId = baseId;
        int counter = 1;
        while (stack.getNode().tryFindChild(uniqueId) != null) {
            uniqueId = baseId + "_" + counter++;
        }
        return uniqueId;
    }

    private String readSynthesizedJson(File outDir, String stackName) {
        if (outDir == null || !outDir.exists()) {
            return EMPTY_JSON;
        }
        try {
            Path outPath = outDir.toPath().toAbsolutePath().normalize();

            List<Path> candidatePaths = List.of(
                    outPath.resolve(STACKS_DIR).resolve(stackName).resolve(CDK_TF_JSON),
                    outPath.resolve("cdktf.out").resolve(STACKS_DIR).resolve(stackName).resolve(CDK_TF_JSON),
                    outPath.resolve(stackName).resolve(CDK_TF_JSON),
                    outPath.resolve("cdktf.out").resolve(stackName).resolve(CDK_TF_JSON),
                    outPath.resolve(CDK_TF_JSON),
                    outPath.resolve("cdktf.out").resolve(CDK_TF_JSON)
            );

            for (Path candidate : candidatePaths) {
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    log.info("Found synthesized CDKTF file for stack '{}' at: {}", stackName, candidate);
                    return ensureProviderConfigured(candidate);
                }
            }

            try (var stream = Files.walk(outPath)) {
                Optional<Path> found = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equalsIgnoreCase(CDK_TF_JSON))
                        .filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equalsIgnoreCase(stackName))
                        .findFirst();

                if (found.isPresent()) {
                    log.info("Found synthesized CDKTF file via recursive search for stack '{}' at: {}", stackName, found.get());
                    return ensureProviderConfigured(found.get());
                }
            }

            try (var stream = Files.walk(outPath)) {
                Optional<Path> found = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equalsIgnoreCase(CDK_TF_JSON))
                        .findFirst();

                if (found.isPresent()) {
                    log.warn("Found fallback CDKTF file for stack '{}' at: {}", stackName, found.get());
                    return ensureProviderConfigured(found.get());
                }
            }
        } catch (Exception e) {
            log.error("Error reading synthesized JSON for stack {}", stackName, e);
        }
        log.warn("Synthesized file cdk.tf.json not found for stack {} in directory {}", stackName, outDir.getAbsolutePath());
        return EMPTY_JSON;
    }

    private String ensureProviderConfigured(Path filePath) {
        try {
            String content = Files.readString(filePath);
            JsonNode tree = jsonMapper.readTree(content);
            if (tree instanceof ObjectNode objNode) {
                if (!objNode.has("provider")) {
                    ObjectNode providerNode = jsonMapper.createObjectNode();
                    var azurermArray = jsonMapper.createArrayNode();
                    ObjectNode azurermObj = jsonMapper.createObjectNode();
                    azurermObj.putObject("features");
                    azurermObj.put("resource_provider_registrations", "none");
                    azurermObj.put("subscription_id", "00000000-0000-0000-0000-000000000000");
                    azurermArray.add(azurermObj);
                    providerNode.set("azurerm", azurermArray);
                    objNode.set("provider", providerNode);

                    String updatedJson = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(objNode);
                    Files.writeString(filePath, updatedJson);
                    return updatedJson;
                }
            }
            return content;
        } catch (Exception e) {
            log.warn("Failed to inject provider configuration into {}", filePath, e);
            try {
                return Files.readString(filePath);
            } catch (Exception ex) {
                return EMPTY_JSON;
            }
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    @PreDestroy
    public void destroy() {
        if (executor != null && !executor.isShutdown()) {
            log.info("Shutting down TerraformGeneratorService executor thread pool...");
            executor.shutdown();
        }
    }
}




