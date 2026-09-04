package com.blackrock.terrain;

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

import static org.assertj.core.api.Assertions.assertThat;

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

        Map<String, StorageAccountDto> accounts;
        try (InputStream is = new FileInputStream(file)) {
            accounts = yamlParserService.streamLargeYaml(is);
        }

        List<String> jsonOutputs = terraformGeneratorService.generateLargeScaleTerraformJson(accounts, "target/cdktf_partition_out");

        assertThat(jsonOutputs).isNotEmpty();
        assertThat(jsonOutputs.get(0)).contains("azurerm_storage_account");
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
}



