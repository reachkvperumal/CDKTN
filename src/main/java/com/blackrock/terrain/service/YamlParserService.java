package com.blackrock.terrain.service;

import com.blackrock.terrain.dto.RootConfig;
import com.blackrock.terrain.dto.StorageAccountDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
}
