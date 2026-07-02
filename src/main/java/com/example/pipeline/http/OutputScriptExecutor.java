package com.example.pipeline.http;

import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeResult;
import org.openjdk.nashorn.api.scripting.ClassFilter;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;
import org.openjdk.nashorn.api.scripting.ScriptObjectMirror;
import org.springframework.stereotype.Component;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OutputScriptExecutor {

    private final NashornScriptEngineFactory factory = new NashornScriptEngineFactory();
    private final ClassFilter denyJavaAccess = className -> false;

    public Object execute(String script, ExecutionContext context) {
        if (script == null || script.trim().isEmpty()) {
            return null;
        }
        ScriptEngine engine = newEngine();
        Bindings bindings = prepareBindings(context);

        String wrappedScript = "(function() {\n"
            + "  var result = null;\n"
            + "  var __returned = (function() {\n"
            + script + "\n"
            + "  })();\n"
            + "  if (__returned !== undefined) { return __returned; }\n"
            + "  return result;\n"
            + "})()";
        try {
            return normalize(engine.eval(wrappedScript, bindings));
        } catch (ScriptException e) {
            throw new ExpressionException("输出脚本执行失败: " + e.getMessage());
        }
    }

    /**
     * 求值条件表达式（SWITCH 的 when）。
     * 返回 JS 真值语义下的布尔结果。
     * 抛出的 ExpressionException 由调用方捕获（视为该 case 不命中）。
     */
    public boolean evaluateCondition(String expression, ExecutionContext context) {
        if (expression == null || expression.trim().isEmpty()) {
            return false;
        }
        ScriptEngine engine = newEngine();
        Bindings bindings = prepareBindings(context);
        try {
            Object value = engine.eval(expression, bindings);
            return isTruthy(value);
        } catch (ScriptException e) {
            throw new ExpressionException("条件表达式执行失败: " + e.getMessage());
        }
    }

    private ScriptEngine newEngine() {
        return factory.getScriptEngine(new String[]{"--language=es6"}, null, denyJavaAccess);
    }

    private Bindings prepareBindings(ExecutionContext context) {
        Bindings bindings = newEngine().createBindings();
        Map<String, Object> nodeBindings = buildNodeBindings(context);
        bindings.put("request", context.getRequestParams());
        bindings.put("nodes", nodeBindings);
        nodeBindings.forEach(bindings::put);
        // 迭代项别名绑定（ITERATE 中使用 SWITCH 时）
        context.getCurrentItem().forEach(bindings::put);
        return bindings;
    }

    private boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        if (value instanceof ScriptObjectMirror) {
            ScriptObjectMirror mirror = (ScriptObjectMirror) value;
            try {
                Boolean asBool = mirror.to(Boolean.class);
                if (Boolean.FALSE.equals(asBool)) {
                    return false;
                }
            } catch (RuntimeException ignored) {
                // 非 boolean 对象（如对象/数组）按真值处理
            }
            return true;
        }
        return true;
    }

    private Map<String, Object> buildNodeBindings(ExecutionContext context) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, NodeResult> entry : context.getAllResults().entrySet()) {
            NodeResult nodeResult = entry.getValue();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("httpStatus", nodeResult.getHttpStatus());
            node.put("data", nodeResult.getData());
            node.put("elapsedMs", nodeResult.getElapsedMs());
            nodes.put(entry.getKey(), node);
        }
        return nodes;
    }

    private Object normalize(Object value) {
        if (value instanceof ScriptObjectMirror) {
            ScriptObjectMirror mirror = (ScriptObjectMirror) value;
            if (mirror.isArray()) {
                List<Object> list = new ArrayList<>();
                for (Object item : mirror.values()) {
                    list.add(normalize(item));
                }
                return list;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : mirror.entrySet()) {
                map.put(entry.getKey(), normalize(entry.getValue()));
            }
            return map;
        }
        if (value instanceof Map) {
            Map<?, ?> raw = (Map<?, ?>) value;
            Map<String, Object> map = new LinkedHashMap<>();
            raw.forEach((key, item) -> map.put(String.valueOf(key), normalize(item)));
            return map;
        }
        if (value instanceof List) {
            List<?> raw = (List<?>) value;
            List<Object> list = new ArrayList<>();
            raw.forEach(item -> list.add(normalize(item)));
            return list;
        }
        return value;
    }
}
