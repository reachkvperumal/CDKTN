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

        private static final String DEFAULT_SOURCE_FILE_1 = "source.yaml";
        private static final String DEFAULT_SOURCE_FILE_2 = "source2.yaml";
        private static final String DEFAULT_STACK_NAME_1 = "SourceStack";
        private static final String DEFAULT_STACK_NAME_2 = "Source2Stack";
        private static final String DEFAULT_OUT_DIR_1 = "cdktf.out/source";
        private static final String DEFAULT_OUT_DIR_2 = "cdktf.out/source2";

        private static final String LOG_HEADER = "=================================================";
        private static final String LOG_START = "Starting CDKTN Terraform Generator Execution...";
        private static final String LOG_COMPLETE = "CDKTN Terraform Generation Completed Successfully!";
        private static final String LOG_CLI_USAGE = "Usage: java -jar cdktn-terraform-generator.jar [input-yaml-path] [stack-name] [output-dir]";

        private final YamlParserService yamlParserService;
        private final TerraformGeneratorService terraformGeneratorService;

        @Override
        public void run(String... args) throws Exception {
            log.info(LOG_HEADER);
            log.info(LOG_START);
            log.info(LOG_HEADER);

            if (args != null && args.length >= 3) {
                String inputPath = args[0];
                String stackName = args[1];
                String outputDir = args[2];

                log.info("CLI Execution Mode: Processing input file '{}', stackName '{}', outputDir '{}'", inputPath, stackName, outputDir);
                File inputFile = new File(inputPath);
                RootConfig config = yamlParserService.parseYamlFile(inputFile);
                String json = terraformGeneratorService.generateTerraformJson(config, stackName, outputDir);
                log.info("Generated Terraform JSON for {} (length: {} chars)", inputPath, json.length());
            } else {
                log.info("No CLI arguments provided. Running default pipeline demonstration files...");
                log.info(LOG_CLI_USAGE);

                processFileIfExists(DEFAULT_SOURCE_FILE_1, DEFAULT_STACK_NAME_1, DEFAULT_OUT_DIR_1);
                processFileIfExists(DEFAULT_SOURCE_FILE_2, DEFAULT_STACK_NAME_2, DEFAULT_OUT_DIR_2);
            }

            log.info(LOG_HEADER);
            log.info(LOG_COMPLETE);
            log.info(LOG_HEADER);
        }

        private void processFileIfExists(String filePath, String stackName, String outputDir) {
            File sourceFile = new File(filePath);
            if (sourceFile.exists()) {
                log.info("Processing file: {}", filePath);
                RootConfig config = yamlParserService.parseYamlFile(sourceFile);
                log.info("Deserialized {} successfully. Storage Accounts count: {}",
                        filePath, config.getStorageAccounts() != null ? config.getStorageAccounts().size() : 0);

                String json = terraformGeneratorService.generateTerraformJson(config, stackName, outputDir);
                log.info("Generated Terraform JSON for {} (length: {} chars)", filePath, json.length());
            }
        }
    }
}


