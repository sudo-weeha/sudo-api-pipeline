package com.example.pipeline.http;

import java.util.Map;

/**
 * API 调用器（含缓存）
 */
public interface ApiInvoker {

    /**
     * 根据 apiCode 调用下游 API
     * @param apiCode API 唯一标识
     * @param resolvedParams 已解析的参数
     * @return 调用结果（包含 HTTP 状态码和响应体）
     */
    ApiInvokeResult invoke(String apiCode, Map<String, Object> resolvedParams);
}
