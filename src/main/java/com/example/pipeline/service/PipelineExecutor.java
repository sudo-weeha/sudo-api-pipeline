package com.example.pipeline.service;

import com.example.pipeline.dto.PipelineExecResponse;
import com.example.pipeline.http.ExpressionException;
import com.example.pipeline.http.ExpressionResolver;
import com.example.pipeline.http.OutputScriptExecutor;
import com.example.pipeline.model.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 编排执行引擎（核心）
 */
@Service
public class PipelineExecutor {

    private static final Logger log = LoggerFactory.getLogger(PipelineExecutor.class);

    @Resource
    private PipelineDefService pipelineDefService;

    @Resource
    private PipelineExecLogService pipelineExecLogService;

    @Resource
    private com.example.pipeline.node.NodeRunner nodeRunner;

    @Resource
    private DagPipelineExecutor dagPipelineExecutor;

    @Resource
    private ExpressionResolver expressionResolver;

    @Resource
    private OutputScriptExecutor outputScriptExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行编排
     */
    public PipelineExecResponse execute(String pipelineCode, Map<String, Object> requestParams) {
        long startTime = System.currentTimeMillis();

        // 1. 获取编排定义
        PipelineDef pipelineDef = pipelineDefService.findByCode(pipelineCode);
        if (pipelineDef == null || pipelineDef.getStatus() == 0) {
            return PipelineExecResponse.error("编排不存在或已禁用: " + pipelineCode);
        }

        PipelineConfig config = pipelineDefService.parseConfig(pipelineDef);
        log.info("开始执行编排: {} ({}), 入参: {}", pipelineDef.getPipelineName(), pipelineCode, requestParams);

        // 2. 校验入参
        if (config.getInputParams() != null) {
            for (InputParamDef param : config.getInputParams()) {
                if (Boolean.TRUE.equals(param.getRequired())) {
                    if (requestParams == null || !requestParams.containsKey(param.getName())) {
                        return PipelineExecResponse.error("缺少必填参数: " + param.getName());
                    }
                }
            }
        }

        // 3. 创建 ExecutionContext
        ExecutionContext context = new ExecutionContext(requestParams != null ? requestParams : Collections.emptyMap());

        // 4. 遍历执行所有节点
        List<Map<String, Object>> nodeErrors = new ArrayList<>();
        try {
            if (config.getEdges() != null && !config.getEdges().isEmpty()) {
                dagPipelineExecutor.run(config, context, nodeErrors);
            } else {
                nodeRunner.run(config.getNodes(), context, nodeErrors);
            }
        } catch (ExpressionException e) {
            log.error("表达式解析失败，终止编排: {}", e.getMessage());
            return PipelineExecResponse.error("表达式解析失败: " + e.getMessage());
        }

        // 5. 解析 outputScript / outputMapping
        Object finalResponse = null;
        try {
            if (config.getOutputScript() != null && !config.getOutputScript().trim().isEmpty()) {
                finalResponse = outputScriptExecutor.execute(config.getOutputScript(), context);
            } else if (config.getOutputMapping() != null) {
                finalResponse = expressionResolver.resolve(config.getOutputMapping(), context);
            }
        } catch (Exception e) {
            log.error("输出结果组装失败: {}", e.getMessage());
            Map<String, Object> errMap = new LinkedHashMap<>();
            errMap.put("error", "输出结果组装失败: " + e.getMessage());
            finalResponse = errMap;
        }

        // 6. 构建响应
        int totalMs = (int) (System.currentTimeMillis() - startTime);
        String status;
        PipelineExecResponse execResponse;

        if (!nodeErrors.isEmpty()) {
            status = "PARTIAL";
            execResponse = PipelineExecResponse.partial(finalResponse, nodeErrors);
        } else {
            status = "SUCCESS";
            execResponse = PipelineExecResponse.success(finalResponse);
        }

        // 7. 保存执行日志
        try {
            PipelineExecLog logEntry = new PipelineExecLog();
            logEntry.setPipelineId(pipelineDef.getId());
            logEntry.setPipelineCode(pipelineCode);
            logEntry.setRequestParams(pipelineExecLogService.toJson(requestParams));
            logEntry.setNodeResults(toJsonNodeResults(context.getAllResults()));
            logEntry.setFinalResponse(pipelineExecLogService.toJson(finalResponse));
            logEntry.setTotalMs(totalMs);
            logEntry.setStatus(status);
            if (!nodeErrors.isEmpty()) {
                logEntry.setErrorMsg(pipelineExecLogService.toJson(nodeErrors));
            }
            pipelineExecLogService.save(logEntry);
        } catch (Exception e) {
            log.error("保存执行日志失败: {}", e.getMessage());
        }

        log.info("编排执行完成: {} → {}, 耗时 {}ms", pipelineCode, status, totalMs);
        return execResponse;
    }

    private String toJsonNodeResults(Map<String, NodeResult> nodeResults) {
        Map<String, Object> results = new LinkedHashMap<>();
        for (Map.Entry<String, NodeResult> entry : nodeResults.entrySet()) {
            NodeResult nr = entry.getValue();
            Map<String, Object> nrMap = new LinkedHashMap<>();
            nrMap.put("httpStatus", nr.getHttpStatus());
            nrMap.put("data", nr.getData());
            nrMap.put("elapsedMs", nr.getElapsedMs());
            results.put(entry.getKey(), nrMap);
        }
        try {
            return objectMapper.writeValueAsString(results);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
