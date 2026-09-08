# CDKTN Terraform Generator - System Architecture & Execution Flow Diagrams

This document details the architectural design, component interactions, execution lifecycle, parsing strategy, parallel partition synthesis, and AST merging mechanisms for the **CDKTN Terraform Generator** application.

---

## System Overview

The CDKTN Terraform Generator is a Spring Boot batch CLI application designed to parse dynamic infrastructure configurations written in YAML and synthesize valid Terraform JSON (`cdk.tf.json`) via the HashiCorp CDK-Terrain (CDKTF) Java SDK.

---

## 1. High-Level End-to-End System Architecture & Lifecycle Flow

This diagram illustrates the entry point orchestration in [`TerrainApplication.java`](file:///Users/kvperumal/office/CDKTN/src/main/java/com/blackrock/terrain/TerrainApplication.java), showing how CLI arguments or default fallback pipeline files trigger parsing, construct tree building, JSII V8 bridge execution, and synthesized artifact generation.

```mermaid
flowchart TD
    subgraph Entry ["1. Invocation & CLI Context"]
        A["CLI / Azure DevOps Pipeline"] -->|PositionArgs: source.yaml, StackName, OutDir| B["TerrainApplication.main()"]
        B --> C["TerrainRunner.run()"]
        C --> D{"CLI Arguments Provided?"}
        D -->|Yes: args >= 3| E["Extract inputPath, stackName, outputDir"]
        D -->|No: Fallback| F["Process Default source.yaml & source2.yaml"]
    end

    subgraph Parsing ["2. YamlParserService Strategy"]
        E & F --> G["YamlParserService.parseYamlFile(File)"]
        G --> H["InputStream Creation"]
        H --> I["YamlParserService.parseYamlStream(InputStream)"]
        I --> J{"Schema Type?"}
        J -->|Standard Root Schema| K["Extract 'storage_accounts' block"]
        J -->|Direct Map Schema| L["Extract root map keys"]
        K & L --> M["validateStorageAccounts()"]
        M -->|Missing/Blank 'id'| N["Throw ConfigurationLoadException"]
        M -->|Valid| O["Return RootConfig DTO Tree"]
    end

    subgraph Synthesis ["3. TerraformGeneratorService & JSII Bridge"]
        O --> P["TerraformGeneratorService.generateTerraformJson()"]
        P --> Q["Instantiate CDKTF App & TerraformStack"]
        Q --> R["Map StorageAccountDto Tree -> CDKTF Constructs"]
        R --> S["Invoke App.synth()"]
        S -->|JSII IPC Bridge| T["Node.js V8 Subprocess (--max-old-space-size=8192)"]
        T --> U["Generate cdk.tf.json AST on Disk"]
        U --> V["readSynthesizedJson() Candidate Search"]
        V --> W["Return Synthesized JSON String"]
    end

    classDef err fill:#f9f2f2,stroke:#d9534f,color:#a94442;
    classDef success fill:#f2f9f2,stroke:#5cb85c,color:#3c763d;
    class N err;
    class W success;
```

---

## 2. High-Throughput Streaming & Standard YAML Parsing Flow

Detailed execution path within [`YamlParserService.java`](file:///Users/kvperumal/office/CDKTN/src/main/java/com/blackrock/terrain/service/YamlParserService.java), covering standard object mapping vs high-throughput tokenized streaming parsing (`streamLargeYaml`) for large datasets (10,000+ lines of YAML).

```mermaid
flowchart TD
    subgraph Stream_Parser ["YamlParserService.streamLargeYaml(InputStream)"]
        A1["Initialize YAMLFactory & YAMLParser"] --> A2["Loop parser.nextToken()"]
        A2 --> A3{"Token == FIELD_NAME?"}
        A3 -->|No| A2
        A3 -->|Yes| A4{"Field Name Match?"}
        
        A4 -->|"field == 'storage_accounts'"| A5["Move to START_OBJECT"]
        A5 --> A6["Read StorageAccountDto via ObjectMapper"]
        A6 --> A7["validateStorageAccountId(saName, dto)"]
        A7 --> A8["Put into Results Map"]
        A8 --> A2

        A4 -->|"field != reserved keyword"| A9["Attempt direct DTO token read"]
        A9 --> A10["validateStorageAccountId(fieldName, dto)"]
        A10 --> A8
    end

    subgraph Validation ["Fast-Fail Validation Pipeline"]
        V1["validateStorageAccountId(accountName, dto)"]
        V1 --> V2{"dto == null OR dto.getId() == null OR isBlank()"}
        V2 -->|True| V3["Throw ConfigurationLoadException"]
        V2 -->|False| V4["Pass Validation"]
    end

    A7 --> V1
    A10 --> V1
```

---

## 3. CDKTF Resource Construct Tree Synthesis Flow

Traces how nested DTOs ([`StorageAccountDto`](file:///Users/kvperumal/office/CDKTN/src/main/java/com/blackrock/terrain/dto/StorageAccountDto.java), [`ContainerDto`](file:///Users/kvperumal/office/CDKTN/src/main/java/com/blackrock/terrain/dto/ContainerDto.java), [`QueueDto`](file:///Users/kvperumal/office/CDKTN/src/main/java/com/blackrock/terrain/dto/QueueDto.java), [`EventSubscriptionDto`](file:///Users/kvperumal/office/CDKTN/src/main/java/com/blackrock/terrain/dto/EventSubscriptionDto.java)) are mapped into CDKTF Open Constructs inside [`TerraformGeneratorService.java`](file:///Users/kvperumal/office/CDKTN/src/main/java/com/blackrock/terrain/service/TerraformGeneratorService.java).

```mermaid
flowchart TD
    subgraph Construct_Building ["Construct Tree Generation"]
        S0["RootConfig.getStorageAccounts()"] --> S1["Iterate Storage Accounts"]
        
        S1 --> S2["buildStorageAccountResource()"]
        S2 --> S3["Generate Construct ID: sa_{accountName}"]
        S3 --> S4["Create TerraformResource: azurerm_storage_account"]
        S4 --> S5["Override Attributes (tier, replication, tags, etc.)"]

        S2 --> S6{"Has Containers?"}
        S6 -->|Yes| S7["buildContainerResource()"]
        S7 --> S8["Generate Construct ID: container_{saName}_{containerName}"]
        S8 --> S9["Create TerraformResource: azurerm_storage_container"]
        S9 --> S10["Override Attributes (replication, retention, etc.)"]

        S7 --> S11{"Has Container Event Subscriptions?"}
        S11 -->|Yes| S12["buildEventSubscriptionResource()"]
        S12 --> S13["Create TerraformResource: azurerm_eventgrid_event_subscription"]

        S2 --> S14{"Has Storage Queues?"}
        S14 -->|Yes| S15["buildQueueResource()"]
        S15 --> S16["Create TerraformResource: azurerm_storage_queue"]

        S2 --> S17{"Has SA Event Subscriptions?"}
        S17 -->|Yes| S18["buildEventSubscriptionResource()"]
    end
```

---

## 4. Parallel Multi-Partition Synthesis Pipeline

For processing multi-partition configurations, `generateLargeScaleTerraformJson(...)` partitions inputs into batches of 500 constructs (`PARTITION_SIZE`) and executes parallel stack synthesis via an `ExecutorService` thread pool.

```mermaid
sequenceDiagram
    autonumber
    participant Client as Invoker
    participant Service as TerraformGeneratorService
    participant Pool as Fixed ThreadPool Executor
    participant Node as JSII Node.js Engine
    participant Disk as File System (cdk.tf.json)

    Client->>Service: generateLargeScaleTerraformJson(allAccounts, outDir)
    Service->>Service: Partition entries into chunks (PARTITION_SIZE = 500)
    
    loop For each partition chunk (0..N)
        Service->>Pool: submit CompletableFuture.supplyAsync()
    end

    par Parallel Thread Execution
        Pool->>Node: Thread 1: Instantiate App + PartitionStack_0 & Synth
        Pool->>Node: Thread 2: Instantiate App + PartitionStack_1 & Synth
        Pool->>Node: Thread N: Instantiate App + PartitionStack_N & Synth
    end

    Node-->>Disk: Write partition_0/stacks/PartitionStack_0/cdk.tf.json
    Node-->>Disk: Write partition_1/stacks/PartitionStack_1/cdk.tf.json
    Node-->>Disk: Write partition_N/stacks/PartitionStack_N/cdk.tf.json

    Service->>Service: CompletableFuture.allOf().join()
    Service->>Disk: readSynthesizedJson() for each partition
    Service-->>Client: Return List<String> synthesized JSON strings
```

---

## 5. AST Deep-Merging & Upsert Strategy (`upsertTerraformJson`)

When updating existing Terraform configurations without destroying unmanaged resource blocks or provider configurations, `upsertTerraformJson` performs a deep merge on the JSON Abstract Syntax Tree (AST).

```mermaid
flowchart TD
    M1["upsertTerraformJson(existingJson, newJson)"] --> M2{"Are JSON inputs valid & non-empty?"}
    M2 -->|No| M3["Return non-empty JSON or original"]
    M2 -->|Yes| M4["Parse into Jackson ObjectNodes (existingObj, newObj)"]
    
    M4 --> M5{"Does newObj contain 'resource' block?"}
    M5 -->|No| M6["Return existingObj"]
    M5 -->|Yes| M7["Locate/Create 'resource' block in existingObj"]
    
    M7 --> M8["Iterate Resource Types (e.g., azurerm_storage_account)"]
    M8 --> M9["Iterate Instance Names (e.g., sa_abcd_dca_abdc)"]
    
    M9 --> M10{"Instance already exists in existing AST?"}
    M10 -->|Yes| M11["Execute deepMerge(existingInstanceNode, newInstanceNode)"]
    M10 -->|No| M12["Insert newInstanceNode.deepCopy()"]
    
    M11 & M12 --> M13["Format pretty-printed JSON AST"]
    M13 --> M14["Return Merged JSON String"]
```

---

## Key Architectural & Implementation Highlights

1. **Fast-Fail Guardrails:**  
   - Mandatory attribute `id` validation is executed during deserialization in [`YamlParserService.java`](file:///Users/kvperumal/office/CDKTN/src/main/java/com/blackrock/terrain/service/YamlParserService.java#L179-L185). Missing or blank IDs trigger a fast-failing [`ConfigurationLoadException`](file:///Users/kvperumal/office/CDKTN/src/main/java/com/blackrock/terrain/exception/ConfigurationLoadException.java).
2. **JSII Interop & Heap Allocation:**  
   - CDKTF interop bridges Java to a Node.js V8 sub-process. For processing large multi-partition constructs, set `NODE_OPTIONS='--max-old-space-size=8192'` in CI/CD pipeline step environments.
3. **AST Structure Preservation:**  
   - `upsertTerraformJson` deep-merges newly synthesized constructs while preserving non-colliding `resource`, `provider`, and `backend` blocks in target JSON AST files.
