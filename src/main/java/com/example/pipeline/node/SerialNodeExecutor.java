package com.example.pipeline.node;

import com.example.pipeline.http.ApiInvokeResult;
import com.example.pipeline.http.ApiInvoker;
import com.example.pipeline.http.ExpressionResolver;
import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SerialNodeExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(SerialNodeExecutor.class);

    @Resource
    private ExpressionResolver expressionResolver;

    @Resource
    private ApiInvoker apiInvoker;

    @Override
    public void execute(NodeDef node, ExecutionContext context) {
        long start = System.currentTimeMillis();
        String nodeId = node.getNodeId();

        try {
            // 1. 解析 inputMapping 中的表达式
            @SuppressWarnings("unchecked")
            Map<String, Object> resolvedParams = (Map<String, Object>) expressionResolver.resolve(node.getInputMapping(), context);

            log.debug("节点 {} inputMapping 解析结果: {}", nodeId, resolvedParams);

            // 2. 调用下游 API
            ApiInvokeResult result = apiInvoker.invoke(node.getApiCode(), resolvedParams);

            // 3. 写入 context（使用下游 API 的真实 HTTP 状态码）
            long elapsedMs = System.currentTimeMillis() - start;
            context.put(nodeId, result.getHttpStatus(), result.getResponseBody(), elapsedMs);
            log.debug("节点 {} 执行完成，httpStatus={}, 耗时 {}ms", nodeId, result.getHttpStatus(), elapsedMs);

        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - start;
            log.error("节点 {} 执行失败: {}", nodeId, e.getMessage());
            // 节点失败不抛异常，记录到 context，后续节点可继续执行
            Map<String, Object> errData = new LinkedHashMap<>();
            errData.put("error", e.getMessage());
            context.put(nodeId, 500, errData, elapsedMs);
        }
    }
}
