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
}

