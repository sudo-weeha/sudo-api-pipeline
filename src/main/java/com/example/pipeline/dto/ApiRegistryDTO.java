package com.example.pipeline.dto;

import lombok.Data;

@Data
public class ApiRegistryDTO {
    private Long id;
    private String apiName;
    private String apiCode;
    private String requestMethod;
    private String requestUrl;
    private String requestHeaders;
    private Integer timeoutMs;
    private Integer retryCount;
    private Integer cacheTtlMin;
    private String description;
    private Integer status;
}
