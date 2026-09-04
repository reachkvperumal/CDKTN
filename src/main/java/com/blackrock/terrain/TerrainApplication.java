package com.blackrock.terrain;

import com.blackrock.terrain.dto.RootConfig;
import com.blackrock.terrain.service.TerraformGeneratorService;
import com.blackrock.terrain.service.YamlParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;

@Slf4j
@SpringBootApplication
public class TerrainApplication {

    public static void main(String[] args) {
        SpringApplication.run(TerrainApplication.class, args);
    }

    @Component
    @Profile("!test")
    @RequiredArgsConstructor
    public static class TerrainRunner implements CommandLineRunner {

        private final YamlParserService yamlParserService;
        private final TerraformGeneratorService terraformGeneratorService;

        @Override
        public void run(String... args) throws Exception {
            log.info("=================================================");
            log.info("Starting CDKTN Terraform Generator Execution...");
            log.info("=================================================");

            File sourceFile1 = new File("source.yaml");
            if (sourceFile1.exists()) {
                log.info("Processing file: source.yaml");
                RootConfig config1 = yamlParserService.parseYamlFile(sourceFile1);
                log.info("Deserialized source.yaml successfully. Storage Accounts count: {}",
                        config1.getStorageAccounts() != null ? config1.getStorageAccounts().size() : 0);

                String json1 = terraformGeneratorService.generateTerraformJson(config1, "SourceStack", "cdktf.out/source");
                log.info("Generated Terraform JSON for source.yaml (length: {} chars)", json1.length());
            }

            File sourceFile2 = new File("source2.yaml");
            if (sourceFile2.exists()) {
                log.info("Processing file: source2.yaml");
                RootConfig config2 = yamlParserService.parseYamlFile(sourceFile2);
                log.info("Deserialized source2.yaml successfully. Storage Accounts count: {}",
                        config2.getStorageAccounts() != null ? config2.getStorageAccounts().size() : 0);

                String json2 = terraformGeneratorService.generateTerraformJson(config2, "Source2Stack", "cdktf.out/source2");
                log.info("Generated Terraform JSON for source2.yaml (length: {} chars)", json2.length());
            }

            log.info("=================================================");
            log.info("CDKTN Terraform Generation Completed Successfully!");
            log.info("=================================================");
        }
    }
}
