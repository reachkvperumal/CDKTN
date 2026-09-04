package com.blackrock.terrain;

import com.blackrock.terrain.dto.RootConfig;
import com.blackrock.terrain.dto.StorageAccountDto;
import com.blackrock.terrain.exception.ConfigurationLoadException;
import com.blackrock.terrain.exception.TerraformRepoInitializationException;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@SpringBootTest
@ActiveProfiles("test")
class YamlParserServiceTest {

    @Autowired
    private YamlParserService yamlParserService;

    @Test
    @DisplayName("Deserialize source.yaml into RootConfig DTO")
    void testParseSourceYaml() throws IOException {
        File file = new File("source.yaml");
        assertThat(file).exists();

        RootConfig rootConfig = yamlParserService.parseYamlFile(file);
        assertThat(rootConfig).isNotNull();
        assertThat(rootConfig.getStorageAccounts()).containsKey("adax_doc_grok");

        StorageAccountDto sa = rootConfig.getStorageAccounts().get("adax_doc_grok");
        assertThat(sa.getId()).isEqualTo("dgrok");
        assertThat(sa.getStorageAccountReleasers()).contains("firstname.lastname@blackrock.com");
        assertThat(sa.getContainers()).containsKey("adax-doc-grok-data");
        assertThat(sa.getBudgets().getAmount()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("Deserialize source2.yaml with anchors and overrides into RootConfig DTO")
    void testParseSource2Yaml() throws IOException {
        File file = new File("source2.yaml");
        assertThat(file).exists();

        RootConfig rootConfig = yamlParserService.parseYamlFile(file);
        assertThat(rootConfig).isNotNull();
        assertThat(rootConfig.getEnvironments()).isNotEmpty();
        assertThat(rootConfig.getStorageAccounts()).containsKeys("accounting", "adax_doc_grok");

        StorageAccountDto accountingSa = rootConfig.getStorageAccounts().get("accounting");
        assertThat(accountingSa.getId()).isEqualTo("accnt");
        assertThat(accountingSa.getTribe()).isEqualTo("acctperfeng");
        assertThat(accountingSa.getTags()).containsEntry("blk-business-unit", "1Aladdin");
        assertThat(accountingSa.getContainers()).containsKey("accounting-data");
    }

    @Test
    @DisplayName("Stream large YAML document via Jackson streaming parser")
    void testStreamLargeYaml() throws IOException {
        File file = new File("source2.yaml");
        assertThat(file).exists();

        try (InputStream is = new FileInputStream(file)) {
            Map<String, StorageAccountDto> accountsMap = yamlParserService.streamLargeYaml(is);
            assertThat(accountsMap).isNotNull();
            assertThat(accountsMap).containsKeys("accounting", "adax_doc_grok");
            assertThat(accountsMap.get("accounting").getId()).isEqualTo("accnt");
        }
    }

    @Test
    @DisplayName("Throw TerraformRepoInitializationException when file does not exist")
    void testThrowTerraformRepoInitializationExceptionOnFileNotFound() {
        File nonExistentFile = new File("non_existent_file.yaml");
        assertThatThrownBy(() -> yamlParserService.parseYamlFile(nonExistentFile))
                .isInstanceOf(TerraformRepoInitializationException.class)
                .hasMessageContaining("Failed to parse YAML file");
    }

    @Test
    @DisplayName("Throw ConfigurationLoadException when storage account ID is missing or blank")
    void testThrowExceptionWhenStorageAccountIdIsMissing() {
        String invalidYaml = """
                storage_accounts:
                  invalid_sa:
                    tribe: test_tribe
                """;

        InputStream is = new java.io.ByteArrayInputStream(invalidYaml.getBytes());
        assertThatThrownBy(() -> yamlParserService.parseYamlStream(is))
                .isInstanceOf(ConfigurationLoadException.class)
                .hasMessageContaining("Mandatory attribute 'id' is missing or blank for storage account 'invalid_sa'");
    }
}



