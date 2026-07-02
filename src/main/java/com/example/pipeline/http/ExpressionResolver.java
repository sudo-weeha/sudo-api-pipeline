package com.example.pipeline.http;

import com.example.pipeline.model.ExecutionContext;

import java.util.List;
import java.util.Map;

/**
 * ${...} 表达式解析引擎
 */
public interface ExpressionResolver {

    /**
     * 递归解析整个 Map 模板中的表达式
     */
    Object resolve(Map<String, Object> template, ExecutionContext context);

    /**
     * 解析单个表达式，如 "${n1.data.data.userid}" → "abc"
     */
    Object resolveExpression(String expression, ExecutionContext context);

    /**
     * 解析表达式为 List
     */
    List<Object> resolveArray(String expression, ExecutionContext context);
}
