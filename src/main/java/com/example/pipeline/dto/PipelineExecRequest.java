package com.example.pipeline.dto;

import java.util.HashMap;
import java.util.Map;

/**
 * 编排执行请求 DTO
 * 接受顶层参数，如 {"id": "123"}，匹配设计文档的 curl 示例
 */
public class PipelineExecRequest extends HashMap<String, Object> {

    /**
     * 获取请求参数 Map
     */
    public Map<String, Object> getParams() {
        return this;
    }
}
