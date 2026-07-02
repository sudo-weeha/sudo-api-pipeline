package com.example.pipeline.node;

import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Component
public class ForkNodeExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(ForkNodeExecutor.class);

    @Resource
    @Lazy
    private NodeRunner nodeRunner;

    @Resource(name = "pipelineTaskExecutor")
    private Executor taskExecutor;

    @Override
    public void execute(NodeDef node, ExecutionContext context) {
        long start = System.currentTimeMillis();
        String forkNodeId = node.getNodeId();
        List<NodeDef> tasks = node.getTasks();

        if (tasks == null || tasks.isEmpty()) {
            log.warn("FORK 节点 {} 没有子任务", forkNodeId);
            Map<String, Object> emptyResult = new LinkedHashMap<>();
            emptyResult.put("data", Collections.emptyList());
            context.put(forkNodeId, 200, emptyResult, System.currentTimeMillis() - start);
            return;
        }

        log.debug("FORK 节点 {} 开始并行执行 {} 个任务", forkNodeId, tasks.size());

        // 并行执行所有子任务
        Map<String, Object> forkResults = new ConcurrentHashMap<>();
        List<Integer> childStatuses = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> futures = tasks.stream()
            .map(task -> CompletableFuture.runAsync(() -> {
                try {
                    ExecutionContext subContext = context.fork();
                    List<Map<String, Object>> childErrors = Collections.synchronizedList(new ArrayList<>());
                    nodeRunner.executeSingle(task, subContext, childErrors);
                    // 将子任务结果合并到父 context
                    var taskResult = subContext.get(task.getNodeId());
                    int childStatus = taskResult != null ? taskResult.getHttpStatus() : 500;
                    childStatuses.add(childStatus);
                    synchronized (context) {
                        for (Map.Entry<String, com.example.pipeline.model.NodeResult> entry : subContext.getAllResults().entrySet()) {
                            com.example.pipeline.model.NodeResult result = entry.getValue();
                            context.put(entry.getKey(), result.getHttpStatus(), result.getData(), result.getElapsedMs());
                        }
                    }
                    forkResults.put(task.getNodeId(), new LinkedHashMap<String, Object>() {{
                        put("httpStatus", childStatus);
                        put("data", taskResult != null ? taskResult.getData() : null);
                    }});
                } catch (Exception e) {
                    log.error("FORK 子任务 {} 执行失败: {}", task.getNodeId(), e.getMessage());
                    childStatuses.add(500);
                    forkResults.put(task.getNodeId(), new LinkedHashMap<String, Object>() {{
                        put("httpStatus", 500);
                        put("error", e.getMessage());
                    }});
                }
            }, taskExecutor))
            .collect(Collectors.toList());

        // 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long elapsedMs = System.currentTimeMillis() - start;
        // FORK 节点的整体状态：所有任务成功则为 200，否则 206
        boolean allSuccess = childStatuses.size() == tasks.size()
            && childStatuses.stream().allMatch(status -> status >= 200 && status < 300);
        int httpStatus = allSuccess ? 200 : 206;

        // 将 FORK 聚合结果写入 context
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", forkResults);
        result.put("totalCount", tasks.size());
        context.put(forkNodeId, httpStatus, result, elapsedMs);
        log.debug("FORK 节点 {} 执行完成，耗时 {}ms", forkNodeId, elapsedMs);
    }
}
