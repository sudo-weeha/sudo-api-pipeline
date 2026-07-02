package com.example.pipeline.service;

import com.example.pipeline.http.ExpressionException;
import com.example.pipeline.http.OutputScriptExecutor;
import com.example.pipeline.model.*;
import com.example.pipeline.node.NodeRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class DagPipelineExecutor {

    private static final Logger log = LoggerFactory.getLogger(DagPipelineExecutor.class);

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_ANY = "ANY";
    private static final String FAIL_FAST = "FAIL_FAST";

    @Resource
    private NodeRunner nodeRunner;

    @Resource
    private OutputScriptExecutor outputScriptExecutor;

    @Resource(name = "pipelineTaskExecutor")
    private Executor taskExecutor;

    public void run(PipelineConfig config, ExecutionContext context, List<Map<String, Object>> nodeErrors) {
        Map<String, NodeDef> nodeMap = config.getNodes().stream()
            .collect(Collectors.toMap(NodeDef::getNodeId, node -> node, (a, b) -> a, LinkedHashMap::new));
        List<EdgeDef> edges = config.getEdges();
        Map<String, List<EdgeDef>> outgoing = buildOutgoing(edges);
        Map<String, Integer> pendingIncoming = buildPendingIncoming(edges, nodeMap.keySet());
        Map<String, Integer> activatedIncoming = new HashMap<>();
        Map<String, NodeState> states = new HashMap<>();
        for (String nodeId : nodeMap.keySet()) {
            activatedIncoming.put(nodeId, 0);
            states.put(nodeId, NodeState.PENDING);
        }

        Deque<String> ready = new ArrayDeque<>();
        activateStartNodes(outgoing.getOrDefault(DagValidator.START, Collections.emptyList()),
            context, pendingIncoming, activatedIncoming, states, ready, outgoing);

        int maxConcurrency = resolveMaxConcurrency(config.getDagConfig());
        boolean failFast = isFailFast(config.getDagConfig());
        CompletionService<NodeRunResult> completionService = new ExecutorCompletionService<>(taskExecutor);
        int running = 0;
        boolean stopScheduling = false;

        while (!ready.isEmpty() || running > 0) {
            while (!stopScheduling && running < maxConcurrency && !ready.isEmpty()) {
                String nodeId = ready.removeFirst();
                if (states.get(nodeId) != NodeState.PENDING) {
                    continue;
                }
                states.put(nodeId, NodeState.RUNNING);
                NodeDef node = nodeMap.get(nodeId);
                completionService.submit(() -> executeNode(node, context, nodeErrors));
                running++;
            }

            if (running == 0) {
                break;
            }

            NodeRunResult result = takeCompleted(completionService);
            running--;
            states.put(result.nodeId, NodeState.DONE);

            boolean success = result.httpStatus >= 200 && result.httpStatus < 300;
            if (failFast && !success) {
                stopScheduling = true;
                ready.clear();
                continue;
            }

            for (EdgeDef edge : outgoing.getOrDefault(result.nodeId, Collections.emptyList())) {
                if (DagValidator.END.equals(edge.getTo())) {
                    continue;
                }
                String child = edge.getTo();
                boolean matched = edgeMatches(edge, result.httpStatus, context);
                advanceChild(child, matched, context, pendingIncoming, activatedIncoming, states, ready, outgoing);
            }
        }
    }

    private NodeRunResult executeNode(NodeDef node, ExecutionContext context, List<Map<String, Object>> nodeErrors) {
        try {
            List<Map<String, Object>> localErrors = new ArrayList<>();
            nodeRunner.executeSingle(node, context, localErrors);
            if (!localErrors.isEmpty()) {
                synchronized (nodeErrors) {
                    nodeErrors.addAll(localErrors);
                }
            }
        } catch (RuntimeException e) {
            long elapsedMs = 0L;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("error", e.getMessage());
            context.put(node.getNodeId(), 500, data, elapsedMs);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("nodeId", node.getNodeId());
            error.put("nodeName", node.getNodeName());
            error.put("httpStatus", 500);
            error.put("message", e.getMessage());
            synchronized (nodeErrors) {
                nodeErrors.add(error);
            }
        }
        NodeResult result = context.get(node.getNodeId());
        int httpStatus = result != null ? result.getHttpStatus() : 500;
        return new NodeRunResult(node.getNodeId(), httpStatus);
    }

    private NodeRunResult takeCompleted(CompletionService<NodeRunResult> completionService) {
        try {
            return completionService.take().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DAG 执行被中断", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("DAG 节点执行失败", e);
        }
    }

    private void activateStartNodes(List<EdgeDef> startEdges,
                                    ExecutionContext context,
                                    Map<String, Integer> pendingIncoming,
                                    Map<String, Integer> activatedIncoming,
                                    Map<String, NodeState> states,
                                    Deque<String> ready,
                                    Map<String, List<EdgeDef>> outgoing) {
        for (EdgeDef edge : startEdges) {
            if (DagValidator.END.equals(edge.getTo())) {
                continue;
            }
            boolean matched = conditionMatches(edge, context);
            advanceChild(edge.getTo(), matched, context, pendingIncoming, activatedIncoming, states, ready, outgoing);
        }
    }

    private void advanceChild(String nodeId,
                              boolean matched,
                              ExecutionContext context,
                              Map<String, Integer> pendingIncoming,
                              Map<String, Integer> activatedIncoming,
                              Map<String, NodeState> states,
                              Deque<String> ready,
                              Map<String, List<EdgeDef>> outgoing) {
        if (states.get(nodeId) != NodeState.PENDING) {
            return;
        }
        if (matched) {
            activatedIncoming.put(nodeId, activatedIncoming.get(nodeId) + 1);
        }
        Integer pending = pendingIncoming.get(nodeId);
        if (pending != null && pending > 0) {
            pendingIncoming.put(nodeId, pending - 1);
        }
        if (pendingIncoming.get(nodeId) == 0) {
            if (activatedIncoming.get(nodeId) > 0) {
                ready.add(nodeId);
            } else {
                markSkipped(nodeId, context, pendingIncoming, activatedIncoming, states, ready, outgoing);
            }
        }
    }

    private void markSkipped(String nodeId,
                             ExecutionContext context,
                             Map<String, Integer> pendingIncoming,
                             Map<String, Integer> activatedIncoming,
                             Map<String, NodeState> states,
                             Deque<String> ready,
                             Map<String, List<EdgeDef>> outgoing) {
        if (states.get(nodeId) != NodeState.PENDING) {
            return;
        }
        states.put(nodeId, NodeState.SKIPPED);
        Map<String, Object> skipped = new LinkedHashMap<>();
        skipped.put("skipped", true);
        skipped.put("data", null);
        context.put(nodeId, 204, skipped, 0L);
        ready.remove(nodeId);
        for (EdgeDef edge : outgoing.getOrDefault(nodeId, Collections.emptyList())) {
            if (!DagValidator.END.equals(edge.getTo())) {
                advanceChild(edge.getTo(), false, context, pendingIncoming, activatedIncoming, states, ready, outgoing);
            }
        }
    }

    private Map<String, List<EdgeDef>> buildOutgoing(List<EdgeDef> edges) {
        Map<String, List<EdgeDef>> outgoing = new HashMap<>();
        for (EdgeDef edge : edges) {
            outgoing.computeIfAbsent(edge.getFrom(), ignored -> new ArrayList<>()).add(edge);
        }
        return outgoing;
    }

    private Map<String, Integer> buildPendingIncoming(List<EdgeDef> edges, Set<String> nodeIds) {
        Map<String, Integer> pending = new HashMap<>();
        for (String nodeId : nodeIds) {
            pending.put(nodeId, 0);
        }
        for (EdgeDef edge : edges) {
            if (!DagValidator.START.equals(edge.getFrom()) && !DagValidator.END.equals(edge.getTo())) {
                pending.put(edge.getTo(), pending.get(edge.getTo()) + 1);
            }
        }
        return pending;
    }

    private boolean edgeMatches(EdgeDef edge, int httpStatus, ExecutionContext context) {
        if (!statusMatches(edge.getOnStatus(), httpStatus)) {
            return false;
        }
        return conditionMatches(edge, context);
    }

    private boolean statusMatches(String onStatus, int httpStatus) {
        String value = onStatus == null || onStatus.trim().isEmpty()
            ? STATUS_SUCCESS
            : onStatus.trim().toUpperCase(Locale.ROOT);
        boolean success = httpStatus >= 200 && httpStatus < 300;
        if (STATUS_ANY.equals(value)) {
            return true;
        }
        if (STATUS_FAILED.equals(value)) {
            return !success;
        }
        return success;
    }

    private boolean conditionMatches(EdgeDef edge, ExecutionContext context) {
        if (edge.getCondition() == null || edge.getCondition().trim().isEmpty()) {
            return true;
        }
        try {
            return outputScriptExecutor.evaluateCondition(edge.getCondition(), context);
        } catch (ExpressionException e) {
            log.warn("DAG 边条件求值失败，按不匹配处理: {} -> {}, err={}",
                edge.getFrom(), edge.getTo(), e.getMessage());
            return false;
        }
    }

    private int resolveMaxConcurrency(DagConfig dagConfig) {
        if (dagConfig == null || dagConfig.getMaxConcurrency() == null || dagConfig.getMaxConcurrency() <= 0) {
            return 8;
        }
        return dagConfig.getMaxConcurrency();
    }

    private boolean isFailFast(DagConfig dagConfig) {
        return dagConfig != null
            && dagConfig.getFailurePolicy() != null
            && FAIL_FAST.equals(dagConfig.getFailurePolicy().trim().toUpperCase(Locale.ROOT));
    }

    private enum NodeState {
        PENDING,
        RUNNING,
        DONE,
        SKIPPED
    }

    private static class NodeRunResult {
        private final String nodeId;
        private final int httpStatus;

        private NodeRunResult(String nodeId, int httpStatus) {
            this.nodeId = nodeId;
            this.httpStatus = httpStatus;
        }
    }
}
