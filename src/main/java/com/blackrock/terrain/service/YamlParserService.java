package com.blackrock.terrain.service;

import com.blackrock.terrain.dto.RootConfig;
import com.blackrock.terrain.dto.StorageAccountDto;
import com.blackrock.terrain.exception.TerraformRepoInitializationException;
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
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class YamlParserService {

    private static final String KEY_STORAGE_ACCOUNTS = "storage_accounts";
    private static final String KEY_DEFAULTS = "defaults";
    private static final String KEY_ENVIRONMENTS = "environments";
    private static final String KEY_BUDGET_DEFAULTS = "budget_defaults";
    private static final String KEY_STORAGE_ACCOUNT_DEFAULTS = "storage_account_defaults";
    private static final String PREFIX_DOT = ".";

    private final ObjectMapper yamlObjectMapper;

    public RootConfig parseYamlFile(File file) {
        try (InputStream is = new FileInputStream(file)) {
            return parseYamlStream(is);
        } catch (Exception e) {
            throw new TerraformRepoInitializationException("Failed to parse YAML file: " + (file != null ? file.getName() : "null"), e);
        }
    }

    public RootConfig parseYamlStream(InputStream inputStream) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(true);
            Yaml yaml = new Yaml(new SafeConstructor(options));

            Object loadedYaml = yaml.load(inputStream);
            if (loadedYaml == null) {
                return new RootConfig();
            }

            RootConfig rootConfig;
            if (loadedYaml instanceof Map<?, ?> rawMap) {
                if (rawMap.containsKey(KEY_STORAGE_ACCOUNTS)) {
                    log.info("Parsing standard root schema with 'storage_accounts' block...");
                    rootConfig = yamlObjectMapper.convertValue(rawMap, RootConfig.class);
                } else {
                    log.info("Parsing direct storage account map schema...");
                    Map<String, StorageAccountDto> storageAccounts = yamlObjectMapper.convertValue(
                            rawMap,
                            new TypeReference<Map<String, StorageAccountDto>>() {}
                    );

                    Map<String, StorageAccountDto> filteredAccounts = new HashMap<>();
                    if (storageAccounts != null) {
                        storageAccounts.forEach((key, val) -> {
                            if (!key.startsWith(PREFIX_DOT) && !key.equals(KEY_DEFAULTS) && !key.equals(KEY_ENVIRONMENTS)) {
                                filteredAccounts.put(key, val);
                            }
                        });
                    }

                    rootConfig = RootConfig.builder()
                            .storageAccounts(filteredAccounts)
                            .build();
                }
            } else {
                rootConfig = new RootConfig();
            }

            validateStorageAccounts(rootConfig.getStorageAccounts());
            return rootConfig;
        } catch (TerraformRepoInitializationException e) {
            throw e;
        } catch (Exception e) {
            throw new TerraformRepoInitializationException("Error occurred while parsing YAML stream", e);
        }
    }

    /**
     * High-throughput streaming parser for large (10K+ line) YAML input documents.
     */
    public Map<String, StorageAccountDto> streamLargeYaml(InputStream inputStream) {
        log.info("Starting high-throughput streaming YAML parse...");
        Map<String, StorageAccountDto> result = new HashMap<>();
        YAMLFactory yamlFactory = YAMLFactory.builder().build();

        try (YAMLParser parser = (YAMLParser) yamlFactory.createParser(inputStream)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME) {
                    String fieldName = parser.currentName();
                    if (KEY_STORAGE_ACCOUNTS.equals(fieldName)) {
                        parser.nextToken(); // Move to START_OBJECT of storage_accounts
                        if (parser.currentToken() == JsonToken.START_OBJECT) {
                            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                                String saName = parser.currentName();
                                parser.nextToken(); // Move to value token
                                StorageAccountDto dto = yamlObjectMapper.readValue(parser, StorageAccountDto.class);
                                if (dto != null) {
                                    validateStorageAccountId(saName, dto);
                                    result.put(saName, dto);
                                }
                            }
                        }
                    } else if (fieldName != null && !fieldName.startsWith(PREFIX_DOT)
                            && !fieldName.equals(KEY_DEFAULTS)
                            && !fieldName.equals(KEY_ENVIRONMENTS)
                            && !fieldName.equals(KEY_BUDGET_DEFAULTS)
                            && !fieldName.equals(KEY_STORAGE_ACCOUNT_DEFAULTS)
                            && !fieldName.equals("azure_data_lake_storage_properties")
                            && !fieldName.equals("tags")) {
                        parser.nextToken();
                        if (parser.currentToken() == JsonToken.START_OBJECT) {
                            try {
                                StorageAccountDto dto = yamlObjectMapper.readValue(parser, StorageAccountDto.class);
                                if (dto != null && (dto.getId() != null || dto.getTribe() != null || (dto.getContainers() != null && !dto.getContainers().isEmpty()))) {
                                    validateStorageAccountId(fieldName, dto);
                                    result.put(fieldName, dto);
                                }
                            } catch (TerraformRepoInitializationException e) {
                                throw e;
                            } catch (Exception e) {
                                log.debug("Skipping non-storage account property token: {}", fieldName);
                            }
                        }
                    }
                }
            }
            log.info("Streaming YAML parse completed. Extracted {} entries.", result.size());
            return result;
        } catch (TerraformRepoInitializationException e) {
            throw e;
        } catch (Exception e) {
            throw new TerraformRepoInitializationException("Failed streaming YAML parsing", e);
        }
    }


    private void validateStorageAccounts(Map<String, StorageAccountDto> storageAccounts) {
        if (storageAccounts == null) return;
        storageAccounts.forEach(this::validateStorageAccountId);
    }

    private void validateStorageAccountId(String accountName, StorageAccountDto dto) {
        if (dto == null || dto.getId() == null || dto.getId().isBlank()) {
            throw new TerraformRepoInitializationException(
                    "Validation Error: Mandatory attribute 'id' is missing or blank for storage account '" + accountName + "'"
            );
        }
    }

}



