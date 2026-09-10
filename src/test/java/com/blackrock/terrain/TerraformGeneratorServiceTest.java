package com.blackrock.terrain;

import com.blackrock.terrain.dto.EventSubscriptionDto;
import com.blackrock.terrain.dto.QueueDto;
import com.blackrock.terrain.dto.RootConfig;
import com.blackrock.terrain.dto.StorageAccountDto;
import com.blackrock.terrain.service.TerraformGeneratorService;
import com.blackrock.terrain.service.YamlParserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import com.blackrock.terrain.exception.ConfigurationLoadException;
import com.blackrock.terrain.exception.TerraformRepoInitializationException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class TerraformGeneratorServiceTest {

    @Autowired
    private YamlParserService yamlParserService;

    @Autowired
    private TerraformGeneratorService terraformGeneratorService;

    @Test
    @DisplayName("Synthesize CDKTF stack and generate Terraform JSON for source2.yaml")
    void testGenerateTerraformJson() throws IOException {
        File file = new File("source2.yaml");
        assertThat(file).exists();

        RootConfig rootConfig = yamlParserService.parseYamlFile(file);
        String tfJson = terraformGeneratorService.generateTerraformJson(rootConfig, "TestStack", "target/cdktf.out");

        assertThat(tfJson).isNotNull();
        assertThat(tfJson).contains("azurerm_storage_account");
        assertThat(tfJson).contains("azurerm_storage_container");
    }

    @Test
    @DisplayName("Synthesize parallel partitioned stacks for high-scale storage accounts")
    void testGenerateLargeScaleTerraformJson() throws IOException {
        File file = new File("source2.yaml");
        assertThat(file).exists();

        Map<String, StorageAccountDto> accounts = yamlParserService.parseYamlFile(file).getStorageAccounts();
        if (accounts == null || accounts.isEmpty()) {
            try (InputStream is = new FileInputStream(file)) {
                accounts = yamlParserService.streamLargeYaml(is);
            }
        }

        assertThat(accounts).as("Parsed storage accounts map from source2.yaml must not be null or empty").isNotNull().isNotEmpty();

        List<String> jsonOutputs = terraformGeneratorService.generateLargeScaleTerraformJson(accounts, "target/cdktf_partition_out");

        assertThat(jsonOutputs).as("Synthesized partition outputs list must not be empty").isNotEmpty();
        assertThat(jsonOutputs.get(0)).as("Synthesized partition JSON must contain azurerm_storage_account").contains("azurerm_storage_account");
    }

    @Test
    @DisplayName("Upsert new resources into existing Terraform JSON structure")
    void testUpsertTerraformJson() throws IOException {
        String existingJson = """
                {
                  "terraform": {
                    "backend": {
                      "azurerm": { "resource_group_name": "rg-existing" }
                    }
                  },
                  "resource": {
                    "azurerm_storage_account": {
                      "sa_existing": {
                        "name": "existingaccount",
                        "account_id": "old_id"
                      }
                    }
                  }
                }
                """;

        String incomingJson = """
                {
                  "resource": {
                    "azurerm_storage_account": {
                      "sa_existing": {
                        "account_id": "updated_id",
                        "tribe": "new_tribe"
                      },
                      "sa_new": {
                        "name": "newaccount",
                        "account_id": "new_id"
                      }
                    }
                  }
                }
                """;

        String resultJson = terraformGeneratorService.upsertTerraformJson(existingJson, incomingJson);

        assertThat(resultJson).contains("rg-existing");
        assertThat(resultJson).contains("sa_existing");
        assertThat(resultJson).contains("updated_id");
        assertThat(resultJson).contains("sa_new");
        assertThat(resultJson).contains("newaccount");
    }

    @Test
    @DisplayName("Upsert YAML DTOs directly into existing Terraform JSON")
    void testUpsertYamlIntoTerraformJson() throws IOException {
        File file = new File("source.yaml");
        assertThat(file).exists();

        RootConfig rootConfig = yamlParserService.parseYamlFile(file);

        String existingJson = """
                {
                  "resource": {
                    "azurerm_storage_account": {
                      "sa_legacy": {
                        "name": "legacy_account"
                      }
                    }
                  }
                }
                """;

        String upsertedJson = terraformGeneratorService.upsertYamlIntoTerraformJson(rootConfig, existingJson, "UpsertStack", "target/cdktf_upsert");

        assertThat(upsertedJson).contains("sa_legacy");
        assertThat(upsertedJson).contains("sa_adax_doc_grok");
        assertThat(upsertedJson).contains("dgrok");
    }

    @Test
    @DisplayName("Verify empty maps and lists in DTOs are omitted from synthesized Terraform JSON")
    void testOmitEmptyCollectionsInTerraformJson() throws IOException {
        StorageAccountDto saDto = StorageAccountDto.builder()
                .id("test_id")
                .tribe("test_tribe")
                .build(); // tags, containers, releasers default to empty collections

        RootConfig rootConfig = RootConfig.builder()
                .storageAccounts(Map.of("test_account", saDto))
                .build();

        String synthesizedJson = terraformGeneratorService.generateTerraformJson(rootConfig, "SanitizedStack", "target/cdktf_sanitized");

        assertThat(synthesizedJson).contains("sa_test_account");
        assertThat(synthesizedJson).doesNotContain("\"tags\": {}");
        assertThat(synthesizedJson).doesNotContain("\"snowflake_environments\": []");
        assertThat(synthesizedJson).doesNotContain("\"storage_account_releasers\": []");
    }

    @Test
    @DisplayName("Throw ConfigurationLoadException when synthesizing storage account with missing ID")
    void testThrowExceptionOnMissingIdInGenerator() {
        StorageAccountDto saDto = StorageAccountDto.builder()
                .tribe("test_tribe")
                .build(); // id is missing/null

        RootConfig rootConfig = RootConfig.builder()
                .storageAccounts(Map.of("sa_no_id", saDto))
                .build();

        assertThatThrownBy(() -> terraformGeneratorService.generateTerraformJson(rootConfig, "InvalidStack", "target/cdktf_invalid"))
                .isInstanceOf(ConfigurationLoadException.class)
                .hasMessageContaining("Mandatory attribute 'id' is missing or blank for storage account 'sa_no_id'");
    }

    @Test
    @DisplayName("Synthesize storage account with queues and event subscriptions into Terraform JSON")
    void testSynthesizeQueuesAndEventSubscriptions() throws IOException {
        QueueDto queueDto = QueueDto.builder()
                .realResourceName("my_custom_queue")
                .retentionDays(14)
                .description("Test Queue Description")
                .build();

        EventSubscriptionDto eventSubDto = EventSubscriptionDto.builder()
                .endpointName("webhook_endpoint")
                .endpointType("WebHook")
                .eventTypes(List.of("Microsoft.Storage.BlobCreated"))
                .subjectBeginsWith("/blobServices/default/containers/input")
                .subjectEndsWith(".csv")
                .includedEventTypes(List.of("Microsoft.Storage.BlobCreated"))
                .enabled(true)
                .build();

        StorageAccountDto saDto = StorageAccountDto.builder()
                .id("queue_sa_id")
                .tribe("queue_tribe")
                .isTest(true)
                .description("Storage Account with Queue and Event Subscriptions")
                .destroySaEnv(List.of("dev", "tst"))
                .queues(Map.of("orders_queue", queueDto))
                .eventSubscriptions(Map.of("blob_created_sub", eventSubDto))
                .build();

        RootConfig rootConfig = RootConfig.builder()
                .storageAccounts(Map.of("queue_sa", saDto))
                .build();

        String synthesizedJson = terraformGeneratorService.generateTerraformJson(rootConfig, "QueueStack", "target/cdktf_queue");

        assertThat(synthesizedJson).contains("azurerm_storage_queue");
        assertThat(synthesizedJson).contains("azurerm_eventgrid_event_subscription");
        assertThat(synthesizedJson).contains("my_custom_queue");
        assertThat(synthesizedJson).contains("webhook_endpoint");
        assertThat(synthesizedJson).contains("Microsoft.Storage.BlobCreated");
        assertThat(synthesizedJson).contains("/blobServices/default/containers/input");
        assertThat(synthesizedJson).contains(".csv");
        assertThat(synthesizedJson).contains("is_test");
        assertThat(synthesizedJson).contains("destroy_sa_env");
    }

    @Test
    @DisplayName("Verify synthesized Terraform JSON passes terraform validate CLI command")
    void testTerraformValidationWithCli() throws Exception {
        File file = new File("source2.yaml");
        assertThat(file).exists();

        RootConfig rootConfig = yamlParserService.parseYamlFile(file);
        String outDir = "target/cdktf_val_test";
        String stackName = "ValStack";

        terraformGeneratorService.generateTerraformJson(rootConfig, stackName, outDir);

        File stackDir = new File(outDir + "/stacks/" + stackName);
        assertThat(stackDir).exists();

        Process initProc = new ProcessBuilder("terraform", "init")
                .directory(stackDir)
                .redirectErrorStream(true)
                .start();
        int initExitCode = initProc.waitFor();
        assertThat(initExitCode).as("terraform init exit code").isEqualTo(0);

        Process valProc = new ProcessBuilder("terraform", "validate")
                .directory(stackDir)
                .redirectErrorStream(true)
                .start();
        String valOutput = new String(valProc.getInputStream().readAllBytes());
        int valExitCode = valProc.waitFor();

        assertThat(valExitCode).as("terraform validate output:\n" + valOutput).isEqualTo(0);
        assertThat(valOutput).contains("The configuration is valid");
    }

    @Test
    @DisplayName("Synthesize Azure RBAC role assignments for readers and writers")
    void testSynthesizeAccessControlRoleAssignments() throws IOException {
        File file = new File("source.yaml");
        assertThat(file).exists();

        RootConfig rootConfig = yamlParserService.parseYamlFile(file);
        String synthesizedJson = terraformGeneratorService.generateTerraformJson(rootConfig, "RbacStack", "target/cdktf_rbac");

        assertThat(synthesizedJson).contains("azurerm_role_assignment");
        assertThat(synthesizedJson).contains("Storage Blob Data Reader");
        assertThat(synthesizedJson).contains("Storage Blob Data Contributor");
        assertThat(synthesizedJson).contains("docgroksvc");
        assertThat(synthesizedJson).contains("bf99c869-5802-4717-9b5d-02539e555280");
    }
}




