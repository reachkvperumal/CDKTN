package com.blackrock.terrain.service;

import com.blackrock.terrain.dto.ContainerDto;
import com.blackrock.terrain.dto.RootConfig;
import com.blackrock.terrain.dto.StorageAccountDto;
import io.cdktn.cdktn.App;
import io.cdktn.cdktn.AppConfig;
import io.cdktn.cdktn.TerraformResource;
import io.cdktn.cdktn.TerraformResourceConfig;
import io.cdktn.cdktn.TerraformStack;
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
public class TerraformGeneratorService {

    private static final int PARTITION_SIZE = 500;
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors())
    );

    public String generateTerraformJson(RootConfig rootConfig, String stackName, String outputDirectory) throws IOException {
        File outDir = new File(outputDirectory);
        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        App app = new App(AppConfig.builder()
                .outdir(outDir.getAbsolutePath())
                .build());

        TerraformStack stack = new TerraformStack(app, stackName);

        if (rootConfig.getStorageAccounts() != null) {
            rootConfig.getStorageAccounts().forEach((accountName, accountDto) -> {
                buildStorageAccountResource(stack, accountName, accountDto);
            });
        }

        app.synth();
        log.info("CDK-Terrain Stack synthesized successfully for stack: {}", stackName);

        Path synthesizedFile = Path.of(outDir.getAbsolutePath(), "stacks", stackName, "cdk.tf.json");
        if (Files.exists(synthesizedFile)) {
            return Files.readString(synthesizedFile);
        } else {
            log.warn("Synthesized file not found at expected path {}, searching in outdir...", synthesizedFile);
            return "{}";
        }
    }

    /**
     * Optimized parallel partitioned stack generation for large constructs (10K+ lines YAML).
     */
    public List<String> generateLargeScaleTerraformJson(Map<String, StorageAccountDto> allAccounts, String baseOutputDir) {
        log.info("Starting large-scale multi-partition synthesis for {} storage account resources...", allAccounts.size());
        List<Map.Entry<String, StorageAccountDto>> entries = new ArrayList<>(allAccounts.entrySet());
        List<List<Map.Entry<String, StorageAccountDto>>> partitions = partition(entries, PARTITION_SIZE);

        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (int i = 0; i < partitions.size(); i++) {
            final int partitionIdx = i;
            final List<Map.Entry<String, StorageAccountDto>> chunk = partitions.get(i);

            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                String stackName = "PartitionStack_" + partitionIdx;
                String partitionOutDirStr = baseOutputDir + "/partition_" + partitionIdx;
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

                Path jsonPath = Path.of(outDir.getAbsolutePath(), "stacks", stackName, "cdk.tf.json");
                try {
                    return Files.exists(jsonPath) ? Files.readString(jsonPath) : "{}";
                } catch (IOException e) {
                    log.error("Failed to read synthesized JSON for stack {}", stackName, e);
                    return "{}";
                }
            }, executor);

            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    private void buildStorageAccountResource(TerraformStack stack, String accountName, StorageAccountDto accountDto) {
        Map<String, Object> saAttributes = new HashMap<>();
        saAttributes.put("name", accountName);
        if (accountDto.getId() != null) saAttributes.put("account_id", accountDto.getId());
        if (accountDto.getTribe() != null) saAttributes.put("tribe", accountDto.getTribe());
        if (accountDto.getPerformance() != null) saAttributes.put("account_tier", accountDto.getPerformance());
        if (accountDto.getRedundancy() != null) saAttributes.put("account_replication_type", accountDto.getRedundancy());
        if (accountDto.getAccessTier() != null) saAttributes.put("access_tier", accountDto.getAccessTier());
        if (accountDto.getTags() != null && !accountDto.getTags().isEmpty()) {
            saAttributes.put("tags", accountDto.getTags());
        }

        TerraformResource saResource = new TerraformResource(stack, "sa_" + accountName, TerraformResourceConfig.builder()
                .terraformResourceType("azurerm_storage_account")
                .build());

        saAttributes.forEach(saResource::addOverride);

        if (accountDto.getContainers() != null) {
            accountDto.getContainers().forEach((containerName, containerDto) -> {
                buildContainerResource(stack, accountName, containerName, containerDto);
            });
        }
    }

    private void buildContainerResource(TerraformStack stack, String saName, String containerName, ContainerDto containerDto) {
        Map<String, Object> containerAttrs = new HashMap<>();
        containerAttrs.put("name", containerName);
        containerAttrs.put("storage_account_name", saName);
        if (containerDto.getReplication() != null) containerAttrs.put("replication", containerDto.getReplication());

        String safeResourceName = ("container_" + saName + "_" + containerName).replaceAll("[^a-zA-Z0-9_]", "_");
        TerraformResource containerResource = new TerraformResource(stack, safeResourceName,
                TerraformResourceConfig.builder()
                        .terraformResourceType("azurerm_storage_container")
                        .build());

        containerAttrs.forEach(containerResource::addOverride);
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}

