package io.arkmem.memory.controller;

import io.arkmem.memory.BadRequestException;
import io.arkmem.memory.api.dto.GenerateInstructionsRequest;
import io.arkmem.memory.api.dto.MemoryCreateRequest;
import io.arkmem.memory.api.dto.MemoryQueryRequest;
import io.arkmem.memory.api.dto.MemoryQueryResponse;
import io.arkmem.memory.api.dto.MemoryResponse;
import io.arkmem.memory.api.dto.MemoryUpdateRequest;
import io.arkmem.memory.api.dto.MessageResponse;
import io.arkmem.memory.api.dto.ResultsResponse;
import io.arkmem.memory.api.dto.SearchMemoryRequest;
import io.arkmem.memory.config.AiProviderSupport;
import io.arkmem.memory.config.ArkMemProperties;
import io.arkmem.memory.llm.InstructionGenerator;
import io.arkmem.memory.llm.InstructionSuggestion;
import io.arkmem.memory.llm.MemoryPromptProvider;
import io.arkmem.memory.MemoryFilter;
import io.arkmem.memory.MemoryHistoryEntry;
import io.arkmem.memory.MemoryOperationResult;
import io.arkmem.memory.MemorySearchResult;
import io.arkmem.memory.MemoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class MemoryController {

    private static final Set<String> EXACT_MEMORY_QUERY_PARAMS = Set.of("user_id", "agent_id", "run_id");
    private static final Set<String> MEMORY_QUERY_PARAMS = Set.of(
            "user_id",
            "agent_id",
            "run_id",
            "scope_mode",
            "limit",
            "offset"
    );
    private static final Set<String> SCOPED_BY_ID_QUERY_PARAMS = Set.of("user_id");

    private final MemoryService memoryService;
    private final InstructionGenerator instructionGenerator;
    private final ArkMemProperties properties;
    private final Environment environment;
    private final MemoryPromptProvider promptProvider;

    public MemoryController(
            MemoryService memoryService,
            InstructionGenerator instructionGenerator,
            ArkMemProperties properties,
            Environment environment,
            MemoryPromptProvider promptProvider
    ) {
        this.memoryService = memoryService;
        this.instructionGenerator = instructionGenerator;
        this.properties = properties;
        this.environment = environment;
        this.promptProvider = promptProvider;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/api/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            memoryService.getAll(new MemoryFilter("__arkmem_readiness__", null, null, Map.of()));
            return ResponseEntity.ok(Map.of(
                    "status", "ready",
                    "database", "ready",
                    "pgvector", "ready",
                    "llm", providerStatus(resolveEffectiveProvider(properties.getLlm().getProvider())),
                    "embedding", providerStatus(resolveEffectiveProvider(properties.getEmbedding().getProvider()))
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "down",
                    "database", "down",
                    "pgvector", "unknown",
                    "llm", "unknown",
                    "embedding", "unknown"
            ));
        }
    }

    @GetMapping("/configure")
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        String llmProvider = resolveEffectiveProvider(properties.getLlm().getProvider());
        String llmApiKey = resolveApiKey(llmProvider, properties.getLlm().getApiKey());
        String embeddingProvider = resolveEffectiveProvider(properties.getEmbedding().getProvider());
        String embeddingApiKey = resolveApiKey(embeddingProvider, properties.getEmbedding().getApiKey());
        config.put("storage", Map.of(
                "provider", properties.getStorage().getProvider(),
                "jdbc_url", environment.getProperty("spring.datasource.url", "")
        ));
        config.put("prompt", Map.of(
                "language", promptProvider.language(),
                "supported_languages", List.of("en", "zh")
        ));
        config.put("llm", Map.of(
                "provider", llmProvider,
                "base_url", AiProviderSupport.resolveBaseUrl(
                        llmProvider,
                        properties.getLlm().getBaseUrl()
                ),
                "model", AiProviderSupport.resolveLlmModel(
                        llmProvider,
                        properties.getLlm().getModel()
                ),
                "api_key", redacted(llmApiKey)
        ));
        config.put("embedding", Map.of(
                "provider", embeddingProvider,
                "base_url", AiProviderSupport.resolveBaseUrl(
                        embeddingProvider,
                        properties.getEmbedding().getBaseUrl()
                ),
                "model", AiProviderSupport.resolveEmbeddingModel(
                        embeddingProvider,
                        properties.getEmbedding().getModel()
                ),
                "api_key", redacted(embeddingApiKey)
        ));
        return config;
    }

    @GetMapping("/configure/providers")
    public Map<String, List<String>> providers() {
        return Map.of(
                "llm", AiProviderSupport.configurableProviders(),
                "embedder", AiProviderSupport.configurableProviders(),
                "prompt_language", List.of("en", "zh")
        );
    }

    @PostMapping("/generate-instructions")
    public InstructionSuggestion generateInstructions(@Valid @RequestBody GenerateInstructionsRequest request) {
        return instructionGenerator.generateInstructions(request.useCase());
    }

    @PostMapping("/memories")
    public ResultsResponse<MemoryOperationResult> add(@Valid @RequestBody MemoryCreateRequest request) {
        return new ResultsResponse<>(memoryService.add(request.toCommand()));
    }

    @GetMapping("/memories")
    public ResultsResponse<MemoryResponse> getAll(
            @RequestParam(name = "user_id", required = false) String userId,
            @RequestParam(name = "agent_id", required = false) String agentId,
            @RequestParam(name = "run_id", required = false) String runId
    ) {
        return listMemories(new MemoryFilter(userId, agentId, runId, Map.of()));
    }

    @GetMapping("/exact/memories")
    public ResultsResponse<MemoryResponse> exactMemories(
            @RequestParam(name = "user_id", required = false) String userId,
            @RequestParam(name = "agent_id", required = false) String agentId,
            @RequestParam(name = "run_id", required = false) String runId,
            HttpServletRequest request
    ) {
        validateExactMemoryScope(request.getParameterMap().keySet(), userId, agentId, runId);
        return listMemories(exactMemoryFilter(userId, agentId, runId));
    }

    @PostMapping("/memories/query")
    public MemoryQueryResponse<MemoryResponse> query(@RequestBody(required = false) MemoryQueryRequest request) {
        MemoryQueryRequest queryRequest = request == null ? new MemoryQueryRequest() : request;
        return queryMemories(queryRequest);
    }

    @GetMapping("/memories/query")
    public MemoryQueryResponse<MemoryResponse> query(HttpServletRequest request) {
        return queryMemories(queryRequestFromParams(request.getParameterMap()));
    }

    private MemoryQueryResponse<MemoryResponse> queryMemories(MemoryQueryRequest queryRequest) {
        MemoryFilter filter = queryFilter(queryRequest);
        List<MemoryResponse> matched = memoryService.getAll(filter)
                .stream()
                .map(MemoryResponse::from)
                .toList();
        int offset = queryRequest.resolvedOffset();
        int limit = queryRequest.resolvedLimit();
        int toIndex = Math.min(matched.size(), offset + limit);
        List<MemoryResponse> page = offset >= matched.size() ? List.of() : matched.subList(offset, toIndex);
        return new MemoryQueryResponse<>(page, matched.size(), limit, offset);
    }

    private static MemoryQueryRequest queryRequestFromParams(Map<String, String[]> params) {
        MemoryQueryRequest request = new MemoryQueryRequest();
        Map<String, Object> filters = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : params.entrySet()) {
            String name = entry.getKey();
            List<String> values = queryParamValues(name, entry.getValue());
            if (MEMORY_QUERY_PARAMS.contains(name)) {
                applyQueryParam(request, name, singleQueryParamValue(name, values));
            } else {
                filters.put(name, metadataQueryParamValue(values));
            }
        }
        request.setFilters(filters);
        return request;
    }

    private static List<String> queryParamValues(String name, String[] values) {
        if (values == null || values.length == 0) {
            throw new BadRequestException(name + " query parameter value is required");
        }
        return Arrays.stream(values)
                .map(value -> value == null ? "" : value)
                .toList();
    }

    private static String singleQueryParamValue(String name, List<String> values) {
        if (values.size() != 1) {
            throw new BadRequestException(name + " must have a single value");
        }
        return values.get(0);
    }

    private static Object metadataQueryParamValue(List<String> values) {
        if (values.size() == 1) {
            return values.get(0);
        }
        return Map.of("in", values);
    }

    private static void applyQueryParam(MemoryQueryRequest request, String name, String value) {
        switch (name) {
            case "user_id" -> request.setUserId(value);
            case "agent_id" -> request.setAgentId(value);
            case "run_id" -> request.setRunId(value);
            case "scope_mode" -> request.setScopeMode(value);
            case "limit" -> request.setLimit(parseIntegerQueryParam(name, value));
            case "offset" -> request.setOffset(parseIntegerQueryParam(name, value));
            default -> throw new BadRequestException("Unsupported query parameter: " + name);
        }
    }

    private static Integer parseIntegerQueryParam(String name, String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException(name + " must be an integer");
        }
    }

    private ResultsResponse<MemoryResponse> listMemories(MemoryFilter filter) {
        List<MemoryResponse> results = memoryService.getAll(filter)
                .stream()
                .map(MemoryResponse::from)
                .toList();
        return new ResultsResponse<>(results);
    }

    @GetMapping("/memories/{memoryId}")
    public MemoryResponse get(
            @PathVariable String memoryId,
            @RequestParam(name = "user_id", required = false) String userId,
            HttpServletRequest request
    ) {
        return MemoryResponse.from(memoryService.get(memoryId, scopedByIdUserId(request, userId)));
    }

    @PostMapping("/search")
    public ResultsResponse<MemorySearchResult> search(@Valid @RequestBody SearchMemoryRequest request) {
        return new ResultsResponse<>(memoryService.search(request.toCommand()));
    }

    @PutMapping("/memories/{memoryId}")
    public MemoryOperationResult update(
            @PathVariable String memoryId,
            @RequestParam(name = "user_id", required = false) String userId,
            @Valid @RequestBody MemoryUpdateRequest updateRequest,
            HttpServletRequest request
    ) {
        return memoryService.update(
                memoryId,
                scopedByIdUserId(request, userId),
                updateRequest.getText(),
                updateRequest.getMetadata()
        );
    }

    @GetMapping("/memories/{memoryId}/history")
    public ResultsResponse<MemoryHistoryEntry> history(
            @PathVariable String memoryId,
            @RequestParam(name = "user_id", required = false) String userId,
            HttpServletRequest request
    ) {
        return new ResultsResponse<>(memoryService.history(memoryId, scopedByIdUserId(request, userId)));
    }

    @DeleteMapping("/memories/{memoryId}")
    public MessageResponse delete(
            @PathVariable String memoryId,
            @RequestParam(name = "user_id", required = false) String userId,
            HttpServletRequest request
    ) {
        memoryService.delete(memoryId, scopedByIdUserId(request, userId));
        return new MessageResponse("Memory deleted successfully");
    }

    @DeleteMapping("/memories")
    public MessageResponse deleteAll(
            @RequestParam(name = "user_id", required = false) String userId,
            @RequestParam(name = "agent_id", required = false) String agentId,
            @RequestParam(name = "run_id", required = false) String runId
    ) {
        int deleted = memoryService.deleteAll(new MemoryFilter(userId, agentId, runId, Map.of()));
        return new MessageResponse("Deleted " + deleted + " memories");
    }

    @PostMapping("/reset")
    public MessageResponse reset() {
        memoryService.reset();
        return new MessageResponse("All memories reset");
    }

    private static String redacted(String value) {
        return value == null || value.isBlank() ? "" : "[redacted]";
    }

    private static void validateExactMemoryScope(Set<String> requestParams, String userId, String agentId, String runId) {
        List<String> unsupportedParams = requestParams.stream()
                .filter(parameter -> !EXACT_MEMORY_QUERY_PARAMS.contains(parameter))
                .toList();
        if (!unsupportedParams.isEmpty()) {
            throw new BadRequestException("Unsupported query parameter: " + unsupportedParams.get(0));
        }
        if (isBlank(userId)) {
            throw new BadRequestException("user_id is required");
        }
        if (isBlank(agentId) && !isBlank(runId)) {
            throw new BadRequestException("agent_id is required when run_id is provided");
        }
    }

    private static MemoryFilter exactMemoryFilter(String userId, String agentId, String runId) {
        if (isBlank(agentId)) {
            return new MemoryFilter(userId, null, null, Map.of(
                    "NOT", List.of(
                            Map.of("agent_id", "*"),
                            Map.of("run_id", "*")
                    )
            ));
        }
        return new MemoryFilter(userId, agentId, runId, Map.of());
    }

    private static MemoryFilter queryFilter(MemoryQueryRequest request) {
        String scopeMode = request.resolvedScopeMode();
        if ("normal".equalsIgnoreCase(scopeMode)) {
            return request.toNormalFilter();
        }
        if ("exact".equalsIgnoreCase(scopeMode)) {
            validateExactMemoryScope(Set.of("user_id", "agent_id", "run_id"), request.getUserId(), request.getAgentId(), request.getRunId());
            MemoryFilter exactFilter = exactMemoryFilter(request.getUserId(), request.getAgentId(), request.getRunId());
            return new MemoryFilter(
                    exactFilter.userId(),
                    exactFilter.agentId(),
                    exactFilter.runId(),
                    mergeFilters(exactFilter.metadata(), request.getFilters())
            );
        }
        throw new BadRequestException("Unsupported scope_mode: " + scopeMode);
    }

    private static Map<String, Object> mergeFilters(Map<String, Object> left, Map<String, Object> right) {
        if (left == null || left.isEmpty()) {
            return right == null ? Map.of() : new LinkedHashMap<>(right);
        }
        if (right == null || right.isEmpty()) {
            return new LinkedHashMap<>(left);
        }
        return Map.of("AND", List.of(left, right));
    }

    private static String scopedByIdUserId(HttpServletRequest request, String userId) {
        List<String> unsupportedParams = request.getParameterMap().keySet().stream()
                .filter(parameter -> !SCOPED_BY_ID_QUERY_PARAMS.contains(parameter))
                .toList();
        if (!unsupportedParams.isEmpty()) {
            throw new BadRequestException("Unsupported query parameter: " + unsupportedParams.get(0));
        }
        if (!request.getParameterMap().containsKey("user_id")) {
            return null;
        }
        if (isBlank(userId)) {
            throw new BadRequestException("user_id cannot be empty");
        }
        String scopedUserId = userId.trim();
        if (scopedUserId.chars().anyMatch(Character::isWhitespace)) {
            throw new BadRequestException("user_id cannot contain whitespace");
        }
        return scopedUserId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String resolveEffectiveProvider(String provider) {
        return AiProviderSupport.resolveEffectiveProvider(
                provider,
                environment.getProperty("OPENAI_API_KEY"),
                environment.getProperty("DASHSCOPE_API_KEY")
        );
    }

    private String resolveApiKey(String provider, String configuredApiKey) {
        return AiProviderSupport.resolveApiKey(
                provider,
                configuredApiKey,
                environment.getProperty("OPENAI_API_KEY"),
                environment.getProperty("DASHSCOPE_API_KEY")
        );
    }

    private static String providerStatus(String provider) {
        return isBlank(provider) ? "unconfigured" : "configured";
    }
}
