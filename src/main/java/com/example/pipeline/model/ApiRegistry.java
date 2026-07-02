package com.example.pipeline.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ApiRegistry {
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
