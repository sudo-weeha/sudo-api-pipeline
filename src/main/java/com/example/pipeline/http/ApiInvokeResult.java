package com.example.pipeline.http;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * API 调用结果
 */
@Data
@AllArgsConstructor
public class ApiInvokeResult {
    private int httpStatus;
    private Object responseBody;
}
