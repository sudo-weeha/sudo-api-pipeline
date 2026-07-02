package com.example.pipeline.controller;

import com.example.pipeline.dto.PipelineDefDTO;
import com.example.pipeline.dto.PipelineExecResponse;
import com.example.pipeline.model.PipelineConfig;
import com.example.pipeline.model.PipelineDef;
import com.example.pipeline.service.PipelineDefService;
import com.example.pipeline.service.PipelineExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class PipelineController {

    @Resource
    private PipelineDefService pipelineDefService;

    @Resource
    private PipelineExecutor pipelineExecutor;

    /**
     * 创建编排定义
     */
    @PostMapping("/pipeline-def")
    public ResponseEntity<Map<String, Object>> create(@RequestBody PipelineDefDTO dto) {
        PipelineDef pipelineDef = pipelineDefService.create(dto);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", pipelineDef);
        result.put("msg", "success");
        return ResponseEntity.ok(result);
    }

    /**
     * 修改编排拓扑
     */
    @PutMapping("/pipeline-def/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody PipelineDefDTO dto) {
        PipelineDef pipelineDef = pipelineDefService.update(id, dto);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", pipelineDef);
        result.put("msg", "success");
        return ResponseEntity.ok(result);
    }

    /**
     * 删除编排
     */
    @DeleteMapping("/pipeline-def/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        pipelineDefService.delete(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("msg", "success");
        return ResponseEntity.ok(result);
    }

    /**
     * 查看编排详情（含完整 JSON）
     */
    @GetMapping("/pipeline-def/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id) {
        PipelineDef pipelineDef = pipelineDefService.findById(id);
        if (pipelineDef == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("code", -1);
            error.put("msg", "编排不存在");
            return ResponseEntity.ok(error);
        }
        PipelineConfig config = pipelineDefService.parseConfig(pipelineDef);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        Map<String, Object> data = new HashMap<>();
        data.put("id", pipelineDef.getId());
        data.put("pipelineName", pipelineDef.getPipelineName());
        data.put("pipelineCode", pipelineDef.getPipelineCode());
        data.put("pipelineConfig", config);
        data.put("description", pipelineDef.getDescription());
        data.put("status", pipelineDef.getStatus());
        result.put("data", data);
        result.put("msg", "success");
        return ResponseEntity.ok(result);
    }

    /**
     * 执行编排，即产出的新 API
     */
    @PostMapping("/api/pipeline/{pipelineCode}/execute")
    public ResponseEntity<PipelineExecResponse> execute(
            @PathVariable String pipelineCode,
            @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> params = normalizeRequestParams(request);
        PipelineExecResponse response = pipelineExecutor.execute(pipelineCode, params);
        return ResponseEntity.ok(response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeRequestParams(Map<String, Object> request) {
        if (request == null) {
            return null;
        }
        Object wrappedParams = request.get("params");
        if (wrappedParams instanceof Map && request.size() == 1) {
            return (Map<String, Object>) wrappedParams;
        }
        return request;
    }
}
