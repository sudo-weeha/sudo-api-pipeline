package com.example.pipeline.service;

import com.example.pipeline.model.PipelineExecLog;
import com.example.pipeline.repository.PipelineExecLogMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Service
public class PipelineExecLogService {

    @Resource
    private PipelineExecLogMapper pipelineExecLogMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void save(PipelineExecLog log) {
        pipelineExecLogMapper.insert(log);
    }

    public List<PipelineExecLog> findByPipelineCode(String pipelineCode) {
        return pipelineExecLogMapper.findByPipelineCode(pipelineCode);
    }

    public List<PipelineExecLog> findAll() {
        return pipelineExecLogMapper.findAll();
    }

    public String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
