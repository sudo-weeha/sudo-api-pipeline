package com.example.pipeline.model;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ExecutionContext {

    private final Map<String, Object> requestParams;
    private final Map<String, NodeResult> nodeResults = new ConcurrentHashMap<>();
    private final Map<String, Object> currentItem = new HashMap<>();

    public ExecutionContext(Map<String, Object> requestParams) {
        this.requestParams = requestParams;
    }

    private ExecutionContext(Map<String, Object> requestParams, Map<String, NodeResult> nodeResults) {
        this.requestParams = requestParams;
        this.nodeResults.putAll(nodeResults);
    }

    public void put(String nodeId, int httpStatus, Object responseBody, long elapsedMs) {
        nodeResults.put(nodeId, new NodeResult(httpStatus, responseBody, elapsedMs));
    }

    public NodeResult get(String nodeId) {
        return nodeResults.get(nodeId);
    }

    public Map<String, Object> getRequestParams() {
        return requestParams;
    }

    public Map<String, NodeResult> getAllResults() {
        return new HashMap<>(nodeResults);
    }

    public ExecutionContext fork() {
        return new ExecutionContext(this.requestParams, this.nodeResults);
    }

    public void putItem(String alias, Object item) {
        currentItem.put(alias, item);
    }

    public Object getItem(String alias) {
        return currentItem.get(alias);
    }

    public Map<String, Object> getCurrentItem() {
        return currentItem;
    }
}
