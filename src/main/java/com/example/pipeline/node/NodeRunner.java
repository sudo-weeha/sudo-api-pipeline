package com.example.pipeline.node;

import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeDef;
import com.example.pipeline.model.NodeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一的节点顺序执行入口。
 * 顶层循环、SWITCH 子节点、ITERATE 子节点均通过此组件执行，
 * 以保证分发逻辑单一、嵌套行为一致。
 */
@Component
public class NodeRunner {

    private static final Logger log = LoggerFactory.getLogger(NodeRunner.class);

    @Resource
    private SerialNodeExecutor serialNodeExecutor;

    @Resource
    private ForkNodeExecutor forkNodeExecutor;

    @Resource
    private IterateNodeExecutor iterateNodeExecutor;

    @Resource
    private SwitchNodeExecutor switchNodeExecutor;

    /**
     * 顺序执行一组节点，收集 httpStatus≥400 的错误到 nodeErrors。
     */
    public void run(List<NodeDef> nodes, ExecutionContext context, List<Map<String, Object>> nodeErrors) {
        if (nodes == null) {
            return;
        }
        for (NodeDef node : nodes) {
            executeSingle(node, context, nodeErrors);
        }
    }

    public void executeSingle(NodeDef node, ExecutionContext context, List<Map<String, Object>> nodeErrors) {
        String nodeType = node.getNodeType();
        log.debug("执行节点: {} (type={})", node.getNodeId(), nodeType);

        switch (nodeType) {
            case "SERIAL":
                serialNodeExecutor.execute(node, context);
                break;
            case "FORK":
                forkNodeExecutor.execute(node, context);
                break;
            case "ITERATE":
                iterateNodeExecutor.execute(node, context);
                break;
            case "SWITCH":
                switchNodeExecutor.execute(node, context, nodeErrors);
                break;
            default:
                throw new RuntimeException("未知的节点类型: " + nodeType);
        }

        NodeResult result = context.get(node.getNodeId());
        if (result != null && result.getHttpStatus() >= 400) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("nodeId", node.getNodeId());
            error.put("nodeName", node.getNodeName());
            error.put("httpStatus", result.getHttpStatus());
            if (result.getData() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = (Map<String, Object>) result.getData();
                error.put("message", dataMap.getOrDefault("error", "Unknown error"));
            }
            nodeErrors.add(error);
        }
    }
}
