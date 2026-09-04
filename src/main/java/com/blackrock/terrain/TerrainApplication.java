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

        private static final String SOURCE_FILE_1 = "source.yaml";
        private static final String SOURCE_FILE_2 = "source2.yaml";
        private static final String STACK_NAME_1 = "SourceStack";
        private static final String STACK_NAME_2 = "Source2Stack";
        private static final String OUT_DIR_1 = "cdktf.out/source";
        private static final String OUT_DIR_2 = "cdktf.out/source2";

        private final YamlParserService yamlParserService;
        private final TerraformGeneratorService terraformGeneratorService;

        @Override
        public void run(String... args) throws Exception {
            log.info("=================================================");
            log.info("Starting CDKTN Terraform Generator Execution...");
            log.info("=================================================");

            File sourceFile1 = new File(SOURCE_FILE_1);
            if (sourceFile1.exists()) {
                log.info("Processing file: {}", SOURCE_FILE_1);
                RootConfig config1 = yamlParserService.parseYamlFile(sourceFile1);
                log.info("Deserialized {} successfully. Storage Accounts count: {}",
                        SOURCE_FILE_1, config1.getStorageAccounts() != null ? config1.getStorageAccounts().size() : 0);

                String json1 = terraformGeneratorService.generateTerraformJson(config1, STACK_NAME_1, OUT_DIR_1);
                log.info("Generated Terraform JSON for {} (length: {} chars)", SOURCE_FILE_1, json1.length());
            }

            File sourceFile2 = new File(SOURCE_FILE_2);
            if (sourceFile2.exists()) {
                log.info("Processing file: {}", SOURCE_FILE_2);
                RootConfig config2 = yamlParserService.parseYamlFile(sourceFile2);
                log.info("Deserialized {} successfully. Storage Accounts count: {}",
                        SOURCE_FILE_2, config2.getStorageAccounts() != null ? config2.getStorageAccounts().size() : 0);

                String json2 = terraformGeneratorService.generateTerraformJson(config2, STACK_NAME_2, OUT_DIR_2);
                log.info("Generated Terraform JSON for {} (length: {} chars)", SOURCE_FILE_2, json2.length());
            }

            log.info("=================================================");
            log.info("CDKTN Terraform Generation Completed Successfully!");
            log.info("=================================================");
        }
    }
}

