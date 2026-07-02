package com.example.pipeline.service;

import com.example.pipeline.dto.PipelineDefDTO;
import com.example.pipeline.model.NodeDef;
import com.example.pipeline.model.PipelineConfig;
import com.example.pipeline.model.PipelineDef;
import com.example.pipeline.repository.ApiRegistryMapper;
import com.example.pipeline.repository.PipelineDefMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Service
public class PipelineDefService {

    private static final Logger log = LoggerFactory.getLogger(PipelineDefService.class);

    @Resource
    private PipelineDefMapper pipelineDefMapper;

    @Resource
    private ApiRegistryMapper apiRegistryMapper;

    @Resource
    private DagValidator dagValidator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<PipelineDef> findAll() {
        return pipelineDefMapper.findAll();
    }

    public PipelineDef findById(Long id) {
        return pipelineDefMapper.findById(id);
    }

    public PipelineDef findByCode(String pipelineCode) {
        return pipelineDefMapper.findByCode(pipelineCode);
    }

    @Transactional
    public PipelineDef create(PipelineDefDTO dto) {
        validatePipelineConfig(dto.getPipelineConfig());

        PipelineDef pipelineDef = new PipelineDef();
        pipelineDef.setPipelineName(dto.getPipelineName());
        pipelineDef.setPipelineCode(dto.getPipelineCode());
        pipelineDef.setPipelineConfig(toJson(dto.getPipelineConfig()));
        pipelineDef.setDescription(dto.getDescription());
        pipelineDef.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);
        pipelineDefMapper.insert(pipelineDef);
        return pipelineDef;
    }

    @Transactional
    public PipelineDef update(Long id, PipelineDefDTO dto) {
        PipelineDef existing = pipelineDefMapper.findById(id);
        if (existing == null) {
            throw new RuntimeException("编排不存在: " + id);
        }
        validatePipelineConfig(dto.getPipelineConfig());

        existing.setPipelineName(dto.getPipelineName());
        existing.setPipelineConfig(toJson(dto.getPipelineConfig()));
        existing.setDescription(dto.getDescription());
        existing.setStatus(dto.getStatus() != null ? dto.getStatus() : 0);
        pipelineDefMapper.update(existing);
        return existing;
    }

    @Transactional
    public void delete(Long id) {
        pipelineDefMapper.deleteById(id);
    }

    public PipelineConfig parseConfig(PipelineDef pipelineDef) {
        try {
            return objectMapper.readValue(pipelineDef.getPipelineConfig(), PipelineConfig.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("编排配置 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 校验 pipeline_config：
     * 1. JSON 结构合法性
     * 2. 引用的 apiCode 必须存在于 api_registry
     */
    private void validatePipelineConfig(PipelineConfig config) {
        if (config == null) {
            throw new RuntimeException("pipelineConfig 不能为空");
        }
        if (config.getEdges() != null && !config.getEdges().isEmpty()) {
            dagValidator.validate(config);
            return;
        }
        // 校验所有节点的 apiCode
        List<String> apiCodes = collectApiCodes(config.getNodes());
        for (String apiCode : apiCodes) {
            if (apiRegistryMapper.findByCode(apiCode) == null) {
                throw new RuntimeException("编排中引用的 API 不存在: " + apiCode);
            }
        }
    }

    private List<String> collectApiCodes(List<NodeDef> nodes) {
        List<String> codes = new ArrayList<>();
        if (nodes == null) return codes;
        for (NodeDef node : nodes) {
            if ("SERIAL".equals(node.getNodeType()) && node.getApiCode() != null) {
                codes.add(node.getApiCode());
            } else if ("FORK".equals(node.getNodeType()) && node.getTasks() != null) {
                codes.addAll(collectApiCodes(node.getTasks()));
            } else if ("ITERATE".equals(node.getNodeType()) && node.getSubPipeline() != null) {
                codes.addAll(collectApiCodes(node.getSubPipeline().getNodes()));
            }
        }
        return codes;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }
}
