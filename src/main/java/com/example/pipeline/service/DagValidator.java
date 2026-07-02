package com.example.pipeline.service;

import com.example.pipeline.model.EdgeDef;
import com.example.pipeline.model.NodeDef;
import com.example.pipeline.model.PipelineConfig;
import com.example.pipeline.model.SwitchCase;
import com.example.pipeline.repository.ApiRegistryMapper;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;

@Component
public class DagValidator {

    public static final String START = "START";
    public static final String END = "END";

    @Resource
    private ApiRegistryMapper apiRegistryMapper;

    public void validate(PipelineConfig config) {
        if (config == null) {
            throw new RuntimeException("pipelineConfig 不能为空");
        }
        List<EdgeDef> edges = config.getEdges();
        if (edges == null || edges.isEmpty()) {
            validateApiReferences(config.getNodes());
            return;
        }

        Map<String, NodeDef> nodeMap = validateNodes(config.getNodes());
        validateEdges(edges, nodeMap);
        validateAcyclic(edges, nodeMap.keySet());
        validateApiReferences(config.getNodes());
    }

    private Map<String, NodeDef> validateNodes(List<NodeDef> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new RuntimeException("DAG 配置 nodes 不能为空");
        }
        Map<String, NodeDef> nodeMap = new LinkedHashMap<>();
        for (NodeDef node : nodes) {
            String nodeId = normalize(node.getNodeId());
            if (nodeId == null) {
                throw new RuntimeException("DAG 节点 nodeId 不能为空");
            }
            if (START.equals(nodeId) || END.equals(nodeId)) {
                throw new RuntimeException("nodeId 不能使用保留字: " + nodeId);
            }
            if (nodeMap.containsKey(nodeId)) {
                throw new RuntimeException("nodeId 重复: " + nodeId);
            }
            nodeMap.put(nodeId, node);
        }
        return nodeMap;
    }

    private void validateEdges(List<EdgeDef> edges, Map<String, NodeDef> nodeMap) {
        boolean hasStart = false;
        boolean hasEnd = false;
        for (EdgeDef edge : edges) {
            String from = normalize(edge.getFrom());
            String to = normalize(edge.getTo());
            if (from == null || to == null) {
                throw new RuntimeException("DAG 边 from/to 不能为空");
            }
            if (END.equals(from)) {
                throw new RuntimeException("END 不能作为边的起点");
            }
            if (START.equals(to)) {
                throw new RuntimeException("START 不能作为边的终点");
            }
            if (!START.equals(from) && !nodeMap.containsKey(from)) {
                throw new RuntimeException("边引用的节点不存在: " + from);
            }
            if (!END.equals(to) && !nodeMap.containsKey(to)) {
                throw new RuntimeException("边引用的节点不存在: " + to);
            }
            if (START.equals(from) && END.equals(to)) {
                throw new RuntimeException("DAG 边不能直接从 START 指向 END");
            }
            if (START.equals(from)) {
                hasStart = true;
            }
            if (END.equals(to)) {
                hasEnd = true;
            }
            validateOnStatus(edge.getOnStatus());
        }
        if (!hasStart) {
            throw new RuntimeException("DAG 配置必须至少包含一条 START -> node 入口边");
        }
        if (!hasEnd) {
            throw new RuntimeException("DAG 配置必须至少包含一条 node -> END 结束边");
        }
    }

    private void validateOnStatus(String onStatus) {
        if (onStatus == null || onStatus.trim().isEmpty()) {
            return;
        }
        String value = onStatus.trim().toUpperCase(Locale.ROOT);
        if (!"SUCCESS".equals(value) && !"FAILED".equals(value) && !"ANY".equals(value)) {
            throw new RuntimeException("DAG 边 onStatus 只支持 SUCCESS / FAILED / ANY: " + onStatus);
        }
    }

    private void validateAcyclic(List<EdgeDef> edges, Set<String> nodeIds) {
        Map<String, List<String>> graph = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String nodeId : nodeIds) {
            graph.put(nodeId, new ArrayList<>());
            inDegree.put(nodeId, 0);
        }
        for (EdgeDef edge : edges) {
            String from = normalize(edge.getFrom());
            String to = normalize(edge.getTo());
            if (START.equals(from) || END.equals(to)) {
                continue;
            }
            graph.get(from).add(to);
            inDegree.put(to, inDegree.get(to) + 1);
        }

        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        int visited = 0;
        while (!queue.isEmpty()) {
            String nodeId = queue.removeFirst();
            visited++;
            for (String child : graph.get(nodeId)) {
                int next = inDegree.get(child) - 1;
                inDegree.put(child, next);
                if (next == 0) {
                    queue.add(child);
                }
            }
        }
        if (visited != nodeIds.size()) {
            throw new RuntimeException("DAG 配置存在环路");
        }
    }

    private void validateApiReferences(List<NodeDef> nodes) {
        if (nodes == null) {
            return;
        }
        for (NodeDef node : nodes) {
            if ("SERIAL".equals(node.getNodeType()) && node.getApiCode() != null) {
                if (apiRegistryMapper.findByCode(node.getApiCode()) == null) {
                    throw new RuntimeException("编排中引用的 API 不存在: " + node.getApiCode());
                }
            }
            if ("FORK".equals(node.getNodeType()) && node.getTasks() != null) {
                validateApiReferences(node.getTasks());
            }
            if ("ITERATE".equals(node.getNodeType()) && node.getSubPipeline() != null) {
                validateApiReferences(node.getSubPipeline().getNodes());
            }
            if ("SWITCH".equals(node.getNodeType())) {
                if (node.getCases() != null) {
                    for (SwitchCase switchCase : node.getCases()) {
                        validateApiReferences(switchCase.getNodes());
                    }
                }
                validateApiReferences(node.getDefaultCase());
            }
        }
    }

    private String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
