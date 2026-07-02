package com.example.pipeline.http;

import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeResult;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ExpressionResolverImpl implements ExpressionResolver {

    private static final Pattern EXPR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    @Override
    public Object resolve(Map<String, Object> template, ExecutionContext context) {
        if (template == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            result.put(entry.getKey(), resolveValue(entry.getValue(), context));
        }
        return result;
    }

    private Object resolveValue(Object value, ExecutionContext context) {
        if (value instanceof String) {
            String str = (String) value;
            Matcher matcher = EXPR_PATTERN.matcher(str);
            if (matcher.find()) {
                // 如果整个字符串就是一个表达式，直接返回解析后的值（保留类型）
                if (str.startsWith("${") && str.endsWith("}") && str.indexOf("${", 2) < 0) {
                    return resolveExpression(str, context);
                }
                // 否则做字符串替换
                StringBuffer sb = new StringBuffer();
                do {
                    Object resolved = resolveExpression(matcher.group(0), context);
                    matcher.appendReplacement(sb, resolved != null ? Matcher.quoteReplacement(resolved.toString()) : "");
                } while (matcher.find());
                matcher.appendTail(sb);
                return sb.toString();
            }
            return str;
        } else if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return resolve(map, context);
        } else if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            return list.stream().map(item -> resolveValue(item, context)).collect(Collectors.toList());
        }
        return value;
    }

    @Override
    public Object resolveExpression(String expression, ExecutionContext context) {
        Matcher matcher = EXPR_PATTERN.matcher(expression);
        if (!matcher.find()) {
            return expression;
        }
        String path = matcher.group(1).trim();
        return resolvePath(path, context);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Object> resolveArray(String expression, ExecutionContext context) {
        Object result = resolveExpression(expression, context);
        if (result instanceof List) {
            return (List<Object>) result;
        }
        if (result == null) {
            return Collections.emptyList();
        }
        // 单个对象包装成 List
        return Collections.singletonList(result);
    }

    /**
     * 解析路径表达式，如：
     *   request.userId    → ExecutionContext.requestParams.userId
     *   n1.data           → NodeResult(n1).data
     *   n1.data.userid    → NodeResult(n1).data.userid
     *   n1.data.orders[0] → NodeResult(n1).data.orders[0]
     *   n1.httpStatus     → NodeResult(n1).httpStatus
     *   order.id          → ExecutionContext.currentItem.order.id
     */
    private Object resolvePath(String path, ExecutionContext context) {
        String[] parts = path.split("\\.", 2);
        String root = parts[0];
        String rest = parts.length > 1 ? parts[1] : null;

        // request.xxx
        if ("request".equals(root)) {
            if (rest == null) {
                return context.getRequestParams();
            }
            return getNestedValue(context.getRequestParams(), rest);
        }

        // itemAlias (ITERATE 中的当前项)
        Object item = context.getItem(root);
        if (item != null) {
            if (rest == null) {
                return item;
            }
            return getNestedValue(item, rest);
        }

        // nodeId (n1, n2, fork1, loop1 ...)
        NodeResult nodeResult = context.get(root);
        if (nodeResult != null) {
            if (rest == null) {
                // 返回整个 NodeResult
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("httpStatus", nodeResult.getHttpStatus());
                wrapper.put("data", nodeResult.getData());
                wrapper.put("elapsedMs", nodeResult.getElapsedMs());
                return wrapper;
            }
            if (rest.startsWith("httpStatus")) {
                return nodeResult.getHttpStatus();
            }
            if (rest.startsWith("data")) {
                String dataRest = rest.length() > 4 ? rest.substring(5) : null; // skip "data."
                if (dataRest == null || dataRest.isEmpty()) {
                    return nodeResult.getData();
                }
                return getNestedValue(nodeResult.getData(), dataRest);
            }
            if (rest.startsWith("elapsedMs")) {
                return nodeResult.getElapsedMs();
            }
            return getNestedValue(nodeResult.getData(), rest);
        }

        throw new ExpressionException("无法解析表达式: ${" + path + "}，节点/变量不存在");
    }

    /**
     * 从对象中获取嵌套字段值，支持 a.b.c 和 a[0].b 语法
     */
    @SuppressWarnings("unchecked")
    private Object getNestedValue(Object obj, String path) {
        if (obj == null || path == null) {
            return null;
        }
        String[] segments = parsePathSegments(path);
        Object current = obj;
        for (String segment : segments) {
            if (current == null) {
                return null;
            }
            if (segment.contains("[") && segment.endsWith("]")) {
                // 数组索引访问: orders[0]
                int bracketIdx = segment.indexOf('[');
                String fieldName = segment.substring(0, bracketIdx);
                int index = Integer.parseInt(segment.substring(bracketIdx + 1, segment.length() - 1));
                if (!fieldName.isEmpty()) {
                    current = getFieldValue(current, fieldName);
                }
                if (current instanceof List) {
                    current = ((List<?>) current).get(index);
                } else if (current.getClass().isArray()) {
                    current = ((Object[]) current)[index];
                } else {
                    throw new ExpressionException("无法对非数组/列表类型进行索引访问: " + segment);
                }
            } else {
                current = getFieldValue(current, segment);
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private Object getFieldValue(Object obj, String fieldName) {
        if (obj instanceof Map) {
            return ((Map<String, Object>) obj).get(fieldName);
        }
        // 对于普通 Java 对象，尝试通过反射获取
        try {
            String getter = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            return obj.getClass().getMethod(getter).invoke(obj);
        } catch (Exception e) {
            // 也尝试 isXxx 形式（boolean）
            try {
                String isGetter = "is" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                return obj.getClass().getMethod(isGetter).invoke(obj);
            } catch (Exception e2) {
                throw new ExpressionException("无法获取字段 '" + fieldName + "' 的值，对象类型: " + obj.getClass().getName());
            }
        }
    }

    /**
     * 将 "data.orders[0].name" 解析为 ["data", "orders[0]", "name"]
     */
    private String[] parsePathSegments(String path) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < path.length()) {
            char ch = path.charAt(i);
            if (ch == '[') {
                int end = path.indexOf(']', i);
                if (end < 0) {
                    throw new ExpressionException("表达式语法错误：'[' 没有匹配的 ']'");
                }
                current.append(path, i, end + 1);
                i = end + 1;
            } else if (ch == '.') {
                if (current.length() > 0) {
                    segments.add(current.toString());
                    current = new StringBuilder();
                }
                i++;
            } else {
                current.append(ch);
                i++;
            }
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        return segments.toArray(new String[0]);
    }
}
