package com.blackrock.terrain.service;

import com.blackrock.terrain.dto.RootConfig;
import com.blackrock.terrain.dto.StorageAccountDto;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class YamlParserService {

    private final ObjectMapper yamlObjectMapper;

    public RootConfig parseYamlFile(File file) throws IOException {
        try (InputStream is = new FileInputStream(file)) {
            return parseYamlStream(is);
        }
    }

    public RootConfig parseYamlStream(InputStream inputStream) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);
        Yaml yaml = new Yaml(new SafeConstructor(options));

        Object loadedYaml = yaml.load(inputStream);
        if (loadedYaml == null) {
            return new RootConfig();
        }

        if (loadedYaml instanceof Map<?, ?> rawMap) {
            if (rawMap.containsKey("storage_accounts")) {
                log.info("Parsing standard root schema with 'storage_accounts' block...");
                return yamlObjectMapper.convertValue(rawMap, RootConfig.class);
            } else {
                log.info("Parsing direct storage account map schema...");
                Map<String, StorageAccountDto> storageAccounts = yamlObjectMapper.convertValue(
                        rawMap,
                        new TypeReference<Map<String, StorageAccountDto>>() {}
                );

                Map<String, StorageAccountDto> filteredAccounts = new HashMap<>();
                if (storageAccounts != null) {
                    storageAccounts.forEach((key, val) -> {
                        if (!key.startsWith(".") && !key.equals("defaults") && !key.equals("environments")) {
                            filteredAccounts.put(key, val);
                        }
                    });
                }

                return RootConfig.builder()
                        .storageAccounts(filteredAccounts)
                        .build();
            }
        }

        return new RootConfig();
    }

    /**
     * High-throughput streaming parser for large (10K+ line) YAML input documents.
     */
    public Map<String, StorageAccountDto> streamLargeYaml(InputStream inputStream) throws IOException {
        log.info("Starting high-throughput streaming YAML parse...");
        Map<String, StorageAccountDto> result = new HashMap<>();
        YAMLFactory yamlFactory = YAMLFactory.builder().build();

        try (YAMLParser parser = (YAMLParser) yamlFactory.createParser(inputStream)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME) {
                    String fieldName = parser.currentName();
                    if ("storage_accounts".equals(fieldName)) {
                        parser.nextToken(); // Move to START_OBJECT of storage_accounts
                        if (parser.currentToken() == JsonToken.START_OBJECT) {
                            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                                String saName = parser.currentName();
                                parser.nextToken(); // Move to value token
                                StorageAccountDto dto = yamlObjectMapper.readValue(parser, StorageAccountDto.class);
                                if (dto != null) {
                                    result.put(saName, dto);
                                }
                            }
                        }
                    } else if (fieldName != null && !fieldName.startsWith(".")
                            && !fieldName.equals("defaults")
                            && !fieldName.equals("environments")
                            && !fieldName.equals("budget_defaults")
                            && !fieldName.equals("storage_account_defaults")) {
                        parser.nextToken();
                        if (parser.currentToken() == JsonToken.START_OBJECT) {
                            try {
                                StorageAccountDto dto = yamlObjectMapper.readValue(parser, StorageAccountDto.class);
                                if (dto != null && (dto.getId() != null || dto.getTribe() != null || dto.getContainers() != null)) {
                                    result.put(fieldName, dto);
                                }
                            } catch (Exception e) {
                                log.debug("Skipping non-storage account property token: {}", fieldName);
                            }
                        }
                    }
                }
            }
        }
        log.info("Streaming YAML parse completed. Extracted {} entries.", result.size());
        return result;
    }

}

