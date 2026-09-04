package com.blackrock.terrain.service;

import com.blackrock.terrain.dto.ContainerDto;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TerraformGeneratorService {

    private static final String RESOURCE_TYPE_STORAGE_ACCOUNT = "azurerm_storage_account";
    private static final String RESOURCE_TYPE_STORAGE_CONTAINER = "azurerm_storage_container";

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

    private static final String KEY_RESOURCE = "resource";
    private static final String STACKS_DIR = "stacks";
    private static final String CDK_TF_JSON = "cdk.tf.json";
    private static final String EMPTY_JSON = "{}";
    private static final String PREFIX_SA_RESOURCE = "sa_";
    private static final String PREFIX_CONTAINER_RESOURCE = "container_";
    private static final String PARTITION_STACK_PREFIX = "PartitionStack_";
    private static final String PARTITION_DIR_PREFIX = "/partition_";
    private static final String EMPTY_OVERRIDE_PATH = "";

    private final ObjectMapper objectMapper;

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

            Path synthesizedFile = Path.of(outDir.getAbsolutePath(), STACKS_DIR, stackName, CDK_TF_JSON);
            if (Files.exists(synthesizedFile)) {
                return Files.readString(synthesizedFile);
            } else {
                log.warn("Synthesized file not found at expected path {}, searching in outdir...", synthesizedFile);
                return EMPTY_JSON;
            }
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

            JsonNode existingTree = objectMapper.readTree(existingJson);
            JsonNode newTree = objectMapper.readTree(newJson);

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

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(existingObj);
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

                    Path jsonPath = Path.of(outDir.getAbsolutePath(), STACKS_DIR, stackName, CDK_TF_JSON);
                    try {
                        return Files.exists(jsonPath) ? Files.readString(jsonPath) : EMPTY_JSON;
                    } catch (IOException e) {
                        log.error("Failed to read synthesized JSON for stack {}", stackName, e);
                        throw new TerraformRepoInitializationException("Failed reading partition synthesized JSON", e);
                    }
                }, executor);

                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
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

        Map<String, Object> saAttributes = new HashMap<>();
        saAttributes.put(ATTR_NAME, accountName);
        saAttributes.put(ATTR_ACCOUNT_ID, accountDto.getId());
        if (accountDto.getTribe() != null) saAttributes.put(ATTR_TRIBE, accountDto.getTribe());
        if (accountDto.getPerformance() != null) saAttributes.put(ATTR_ACCOUNT_TIER, accountDto.getPerformance());
        if (accountDto.getRedundancy() != null) saAttributes.put(ATTR_ACCOUNT_REPLICATION_TYPE, accountDto.getRedundancy());
        if (accountDto.getAccessTier() != null) saAttributes.put(ATTR_ACCESS_TIER, accountDto.getAccessTier());


        if (isNonEmpty(accountDto.getTags())) {
            saAttributes.put(ATTR_TAGS, accountDto.getTags());
        }
        if (isNonEmpty(accountDto.getAzureDataLakeStorageProperties())) {
            saAttributes.put(ATTR_AZURE_DATA_LAKE_STORAGE_PROPERTIES, accountDto.getAzureDataLakeStorageProperties());
        }
        if (isNonEmpty(accountDto.getSnowflakeEnvironments())) {
            saAttributes.put(ATTR_SNOWFLAKE_ENVIRONMENTS, accountDto.getSnowflakeEnvironments());
        }
        if (isNonEmpty(accountDto.getStorageAccountReleasers())) {
            saAttributes.put(ATTR_STORAGE_ACCOUNT_RELEASERS, accountDto.getStorageAccountReleasers());
        }
        if (isNonEmpty(accountDto.getStorageAccountOwners())) {
            saAttributes.put(ATTR_STORAGE_ACCOUNT_OWNERS, accountDto.getStorageAccountOwners());
        }

        TerraformResource saResource = new TerraformResource(stack, PREFIX_SA_RESOURCE + accountName, TerraformResourceConfig.builder()
                .terraformResourceType(RESOURCE_TYPE_STORAGE_ACCOUNT)
                .build());

        saAttributes.forEach(saResource::addOverride);

        if (isNonEmpty(accountDto.getContainers())) {
            accountDto.getContainers().forEach((containerName, containerDto) -> {
                buildContainerResource(stack, accountName, containerName, containerDto);
            });
        }
    }

    private void buildContainerResource(TerraformStack stack, String saName, String containerName, ContainerDto containerDto) {
        Map<String, Object> containerAttrs = new HashMap<>();
        containerAttrs.put(ATTR_NAME, containerName);
        containerAttrs.put(ATTR_STORAGE_ACCOUNT_NAME, saName);
        if (containerDto.getReplication() != null) containerAttrs.put(ATTR_REPLICATION, containerDto.getReplication());

        if (isNonEmpty(containerDto.getContainerOwners())) {
            containerAttrs.put(ATTR_CONTAINER_OWNERS, containerDto.getContainerOwners());
        }
        if (isNonEmpty(containerDto.getEnvironments())) {
            containerAttrs.put(ATTR_ENVIRONMENTS, containerDto.getEnvironments());
        }
        if (isNonEmpty(containerDto.getLifecycleManagement())) {
            containerAttrs.put(ATTR_LIFECYCLE_MANAGEMENT, containerDto.getLifecycleManagement());
        }

        String safeResourceName = (PREFIX_CONTAINER_RESOURCE + saName + "_" + containerName).replaceAll("[^a-zA-Z0-9_]", "_");
        TerraformResource containerResource = new TerraformResource(stack, safeResourceName,
                TerraformResourceConfig.builder()
                        .terraformResourceType(RESOURCE_TYPE_STORAGE_CONTAINER)
                        .build());

        containerAttrs.forEach(containerResource::addOverride);
    }

    private boolean isNonEmpty(Collection<?> col) {
        return col != null && !col.isEmpty();
    }

    private boolean isNonEmpty(Map<?, ?> map) {
        return map != null && !map.isEmpty();
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}




