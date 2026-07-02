package com.example.pipeline.controller;

import com.example.pipeline.http.ExpressionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ExpressionException.class)
    public ResponseEntity<Map<String, Object>> handleExpressionException(ExpressionException e) {
        log.error("表达式解析异常: {}", e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("code", -1);
        result.put("data", null);
        result.put("msg", "表达式解析失败: " + e.getMessage());
        return ResponseEntity.ok(result);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        log.error("运行时异常: {}", e.getMessage(), e);
        Map<String, Object> result = new HashMap<>();
        result.put("code", -1);
        result.put("data", null);
        result.put("msg", e.getMessage());
        return ResponseEntity.ok(result);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("未知异常: {}", e.getMessage(), e);
        Map<String, Object> result = new HashMap<>();
        result.put("code", -1);
        result.put("data", null);
        result.put("msg", "服务器内部错误: " + e.getMessage());
        return ResponseEntity.ok(result);
    }
}
