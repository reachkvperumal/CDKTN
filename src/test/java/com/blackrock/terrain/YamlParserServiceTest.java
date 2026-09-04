package com.blackrock.terrain;

import com.blackrock.terrain.dto.RootConfig;
import com.blackrock.terrain.dto.StorageAccountDto;
import com.blackrock.terrain.service.YamlParserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

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
}
