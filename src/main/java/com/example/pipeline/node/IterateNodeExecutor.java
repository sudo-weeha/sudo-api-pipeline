package com.example.pipeline.node;

import com.example.pipeline.http.ExpressionException;
import com.example.pipeline.http.ExpressionResolver;
import com.example.pipeline.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Component
public class IterateNodeExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(IterateNodeExecutor.class);

    @Resource
    @org.springframework.context.annotation.Lazy
    private NodeRunner nodeRunner;

    @Resource
    private ExpressionResolver expressionResolver;

    @Resource(name = "pipelineTaskExecutor")
    private Executor taskExecutor;

    @Override
    @SuppressWarnings("unchecked")
    public void execute(NodeDef node, ExecutionContext context) {
        String iterateNodeId = node.getNodeId();
        long start = System.currentTimeMillis();

        // 1. 解析 iterateOn 表达式
        Object raw = expressionResolver.resolveExpression(node.getIterateOn(), context);

        // 2. 如果有 splitBy，切割字符串；否则直接当数组
        List<Object> items;
        if (node.getSplitBy() != null && !node.getSplitBy().isEmpty() && raw instanceof String) {
            items = Arrays.stream(((String) raw).split(node.getSplitBy()))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
            log.debug("ITERATE 节点 {} 字符串切割: splitBy='{}' → {} 个元素", iterateNodeId, node.getSplitBy(), items.size());
        } else if (raw instanceof List) {
            items = (List<Object>) raw;
            log.debug("ITERATE 节点 {} 数组遍历: {} 个元素", iterateNodeId, items.size());
        } else if (raw instanceof Map) {
            // 尝试从 Map 中提取数组
            items = new ArrayList<>();
            log.warn("ITERATE 节点 {} iterateOn 解析为 Map，尝试遍历 values", iterateNodeId);
            items.addAll(((Map<String, Object>) raw).values());
        } else {
            throw new ExpressionException("ITERATE 节点 " + iterateNodeId + " 只能遍历数组或带 splitBy 的字符串，实际类型: " +
                (raw != null ? raw.getClass().getName() : "null"));
        }

        if (items.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("data", Collections.emptyList());
            result.put("totalCount", 0);
            result.put("successCount", 0);
            result.put("failedCount", 0);
            context.put(iterateNodeId, 200, result, System.currentTimeMillis() - start);
            return;
        }

        // 3. 信号量控制并发
        int maxConcurrency = node.getMaxConcurrency() != null ? node.getMaxConcurrency() : 5;
        Semaphore semaphore = new Semaphore(maxConcurrency);

        // 4. 获取子编排的节点列表
        List<NodeDef> subNodes = node.getSubPipeline() != null ? node.getSubPipeline().getNodes() : Collections.emptyList();
        String itemAlias = node.getItemAlias();

        // 5. 每个元素创建一个子任务
        List<CompletableFuture<Map<String, Object>>> futures = items.stream()
            .map(item -> CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        return executeSubPipeline(item, itemAlias, subNodes, context, iterateNodeId);
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Map<String, Object> errMap = new LinkedHashMap<>();
                    errMap.put("error", "线程被中断: " + e.getMessage());
                    return errMap;
                } catch (Exception e) {
                    log.error("ITERATE 子任务执行失败: {}", e.getMessage());
                    Map<String, Object> errMap = new LinkedHashMap<>();
                    errMap.put("error", e.getMessage());
                    return errMap;
                }
            }, taskExecutor))
            .collect(Collectors.toList());

        // 6. 等待所有子任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 7. 收集结果，区分成功/失败
        List<Map<String, Object>> results = new ArrayList<>();
        long successCount = 0, failedCount = 0;
        for (int i = 0; i < futures.size(); i++) {
            try {
                Map<String, Object> result = futures.get(i).get();
                results.add(result);
                if (isIterationFailed(result)) {
                    failedCount++;
                } else {
                    successCount++;
                }
            } catch (ExecutionException e) {
                Map<String, Object> errItem = new LinkedHashMap<>();
                errItem.put("item", items.get(i));
                errItem.put("error", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                results.add(errItem);
                failedCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Map<String, Object> errItem = new LinkedHashMap<>();
                errItem.put("error", "线程被中断");
                results.add(errItem);
                failedCount++;
            }
        }

        // 8. 写入 context
        long elapsedMs = System.currentTimeMillis() - start;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", results);
        result.put("totalCount", items.size());
        result.put("successCount", successCount);
        result.put("failedCount", failedCount);
        int httpStatus = failedCount == 0 ? 200 : 206;
        context.put(iterateNodeId, httpStatus, result, elapsedMs);
        log.debug("ITERATE 节点 {} 执行完成: total={}, success={}, failed={}, elapsed={}ms",
            iterateNodeId, items.size(), successCount, failedCount, elapsedMs);
    }

    private Map<String, Object> executeSubPipeline(Object item, String itemAlias,
                                                    List<NodeDef> subNodes,
                                                    ExecutionContext parentContext,
                                                    String iterateNodeId) {
        // 创建子上下文
        ExecutionContext subContext = parentContext.fork();
        subContext.putItem(itemAlias, item);

        Map<String, Object> iterationResult = new LinkedHashMap<>();

        // 记录当前迭代项
        if (item instanceof Map) {
            iterationResult.put(itemAlias, item);
        } else {
            iterationResult.put("item", item);
        }

        // 按序执行子编排中的每个节点（支持 SERIAL/FORK/ITERATE/SWITCH 嵌套）
        try {
            nodeRunner.run(subNodes, subContext, new ArrayList<>());
        } catch (Exception e) {
            log.error("ITERATE 子编排执行失败: {}", e.getMessage());
        }

        // 收集子节点的执行结果
        for (NodeDef subNode : subNodes) {
            NodeResult nodeResult = subContext.get(subNode.getNodeId());
            if (nodeResult != null) {
                Map<String, Object> nodeResultMap = new LinkedHashMap<>();
                nodeResultMap.put("httpStatus", nodeResult.getHttpStatus());
                nodeResultMap.put("data", nodeResult.getData());
                iterationResult.put(subNode.getNodeId(), nodeResultMap);
            }
        }

        return iterationResult;
    }

    @SuppressWarnings("unchecked")
    private boolean isIterationFailed(Map<String, Object> result) {
        if (result.containsKey("error")) {
            return true;
        }
        for (Object value : result.values()) {
            if (value instanceof Map) {
                Object status = ((Map<String, Object>) value).get("httpStatus");
                if (status instanceof Number) {
                    int code = ((Number) status).intValue();
                    if (code < 200 || code >= 300) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
