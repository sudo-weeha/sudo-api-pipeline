# 条件分支（SWITCH 节点）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为编排引擎新增 SWITCH 节点，支持基于 Nashorn JS 表达式 `when` 的互斥多分支条件路由，并配套 UI 编辑入口与一个完整的数据库示例编排。

**架构：** 新增 `SwitchNodeExecutor` 评估各 case 的 `when`（JS 表达式，复用 `OutputScriptExecutor` 的 Nashorn 绑定），命中第一个为 true 的 case 后顺序执行其子节点。抽出独立 `NodeRunner` 组件统一"顺序执行一组 nodes + 收集错误"，让顶层循环、SWITCH 子节点、ITERATE 子节点共用同一执行入口，天然支持任意嵌套（SWITCH 套 SWITCH、ITERATE 套 SWITCH）。

**技术栈：** Java 11、Spring Boot 2.7.18、Nashorn 15.4、Jackson、JUnit 5 + Mockito（spring-boot-starter-test 已在 pom）。

**设计依据：** `docs/superpowers/specs/2026-07-01-switch-node-design.md`

---

## 文件结构

| 文件 | 职责 | 动作 |
|---|---|---|
| `model/SwitchCase.java` | SWITCH 的单个分支：`when` JS 表达式 + `nodes` 子节点列表 | 创建 |
| `model/NodeDef.java` | 节点定义，加 `cases`、`defaultCase` 字段 | 修改 |
| `node/NodeRunner.java` | 统一的节点顺序执行入口，含 switch 分发 + 错误收集 | 创建 |
| `node/SwitchNodeExecutor.java` | SWITCH 节点执行器：评估 `when`、调度命中 case 的子节点、写 matchedCase | 创建 |
| `http/OutputScriptExecutor.java` | 抽出 `prepareBindings` + 新增 `evaluateCondition` 复用 Nashorn | 修改 |
| `node/IterateNodeExecutor.java` | 子节点执行改为调 `NodeRunner`，支持 ITERATE 内嵌 SWITCH/FORK/ITERATE | 修改 |
| `service/PipelineExecutor.java` | 顶层循环改为调 `NodeRunner`，移除内联 `executeNode` | 修改 |
| `controller/MockApiController.java` | 新增 3 个 mock 端点 | 修改 |
| `config/DemoDataInitializer.java` | 注册 3 个 API + 写入 `USER_TYPE_SWITCH` 编排 | 修改 |
| `templates/pipeline/form.html` | `addNode('SWITCH')` 卡片 + 预览渲染 + 提交回带 | 修改 |
| `src/test/java/com/example/pipeline/node/SwitchNodeExecutorTest.java` | SWITCH 行为单元测试 | 创建 |
| `src/test/java/com/example/pipeline/node/NodeRunnerTest.java` | NodeRunner 递归调度测试 | 创建 |

### 数据流与上下文约定

- `NodeRunner.run(List<NodeDef>, ExecutionContext, List<Map<String,Object>> nodeErrors)`：接收**外部传入的 context**。
  - 顶层调用：`PipelineExecutor` 传主 context。
  - SWITCH 调用：传**父 context**（子节点结果直接进父 context，便于后续节点 / outputScript 引用 `${n2.data}`）。
  - ITERATE 调用：传 `parentContext.fork()` 后的 subContext（保留现有隔离语义）。
- SWITCH 节点自身在 context 写一条 `NodeResult`：`httpStatus=200`，`data={"matchedCase": <索引>}`。
- `matchedCase` 取值：命中 `cases[i]` → `i`；命中 `defaultCase` → `-2`；全未命中且无 default → `-1`。

### 表达式路径对齐（重要）

mock `/mock-api/user/type` 返回响应体 `{code:"200", data:{userid, type}}`。
`SerialNodeExecutor` 把响应体存进 `NodeResult.data`，所以 JS 里访问为：
`n1.data.data.type`（外层 `.data` = NodeResult.data = 响应体；内层 `.data.type` = 响应体的 data.type）。
本计划所有 `when` 与 outputScript 均使用此正确路径。

---

## 任务 0：环境基线确认

**文件：** 无（仅验证）

- [ ] **步骤 1：确认应用当前可启动、测试无现有用例**

运行：
```bash
mvn -q -DskipTests compile
```
预期：BUILD SUCCESS。

运行：
```bash
find src/test -type f 2>/dev/null | wc -l
```
预期：`0`（无现有测试，本计划将建立首个测试基座）。

---

## 任务 1：`SwitchCase` 模型

**文件：**
- 创建：`src/main/java/com/example/pipeline/model/SwitchCase.java`

- [ ] **步骤 1：创建 `SwitchCase`**

```java
package com.example.pipeline.model;

import lombok.Data;
import java.util.List;

/**
 * SWITCH 节点的单个分支
 */
@Data
public class SwitchCase {
    /** JS 表达式，求值为 true 则命中此 case */
    private String when;
    /** 命中后顺序执行的子节点 */
    private List<NodeDef> nodes;
}
```

- [ ] **步骤 2：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS。

- [ ] **步骤 3：Commit**

```bash
git add src/main/java/com/example/pipeline/model/SwitchCase.java
git commit -m "feat(model): 新增 SwitchCase 分支模型"
```

> 注：项目当前非 git 仓库。若 `git commit` 报 `not a git repository`，先执行 `git init && git add -A && git commit -m "chore: 初始提交"` 建仓，再继续后续 commit。仅做一次。

---

## 任务 2：`NodeDef` 增加 SWITCH 字段

**文件：**
- 修改：`src/main/java/com/example/pipeline/model/NodeDef.java`

- [ ] **步骤 1：加 `cases`、`defaultCase` 字段**

在 `NodeDef.java` 现有 `private SubPipelineDef subPipeline;` 之后追加：

```java
    private List<SwitchCase> cases;        // SWITCH 节点使用
    private List<NodeDef> defaultCase;     // SWITCH 节点使用（可选）
```

并补 import（文件顶部已有 `import java.util.List;`，无需新增）。

- [ ] **步骤 2：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS。

- [ ] **步骤 3：Commit**

```bash
git add src/main/java/com/example/pipeline/model/NodeDef.java
git commit -m "feat(model): NodeDef 增加 SWITCH 的 cases/defaultCase 字段"
```

---

## 任务 3：`OutputScriptExecutor` 抽出绑定 + 条件求值

**文件：**
- 修改：`src/main/java/com/example/pipeline/http/OutputScriptExecutor.java`

- [ ] **步骤 1：抽出 `prepareBindings`，新增 `evaluateCondition`**

将 `execute` 方法中准备绑定的逻辑抽成 `prepareBindings`，并新增条件求值方法。完整替换 `OutputScriptExecutor.java` 内容为：

```java
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
     * 返回值的布尔语义：true 仅当求值为 JS 真值且非 null/undefined。
     * 抛出的 ScriptException 由调用方捕获（视为该 case 不命中）。
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
            if (mirror.isUndefined()) {
                return false;
            }
            if (Boolean.FALSE.equals(mirror.to(Boolean.class))) {
                return false;
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
```

- [ ] **步骤 2：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS。

- [ ] **步骤 3：Commit**

```bash
git add src/main/java/com/example/pipeline/http/OutputScriptExecutor.java
git commit -m "refactor(http): 抽出 prepareBindings，新增 evaluateCondition 供 SWITCH 复用"
```

---

## 任务 4：`NodeRunner` 统一执行入口

**文件：**
- 创建：`src/main/java/com/example/pipeline/node/NodeRunner.java`

- [ ] **步骤 1：创建 `NodeRunner`**

```java
package com.example.pipeline.node;

import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeDef;
import com.example.pipeline.model.NodeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一的节点顺序执行入口。
 * 顶层循环、SWITCH 子节点、ITERATE 子节点均通过此组件执行，
 * 以保证分发逻辑单一、嵌套行为一致。
 */
@Component
public class NodeRunner {

    private static final Logger log = LoggerFactory.getLogger(NodeRunner.class);

    @Resource
    private SerialNodeExecutor serialNodeExecutor;

    @Resource
    private ForkNodeExecutor forkNodeExecutor;

    @Resource
    private IterateNodeExecutor iterateNodeExecutor;

    @Resource
    private SwitchNodeExecutor switchNodeExecutor;

    /**
     * 顺序执行一组节点，收集 httpStatus≥400 的错误到 nodeErrors。
     */
    public void run(List<NodeDef> nodes, ExecutionContext context, List<Map<String, Object>> nodeErrors) {
        if (nodes == null) {
            return;
        }
        for (NodeDef node : nodes) {
            executeNode(node, context, nodeErrors);
        }
    }

    private void executeNode(NodeDef node, ExecutionContext context, List<Map<String, Object>> nodeErrors) {
        String nodeType = node.getNodeType();
        log.debug("执行节点: {} (type={})", node.getNodeId(), nodeType);

        switch (nodeType) {
            case "SERIAL":
                serialNodeExecutor.execute(node, context);
                break;
            case "FORK":
                forkNodeExecutor.execute(node, context);
                break;
            case "ITERATE":
                iterateNodeExecutor.execute(node, context);
                break;
            case "SWITCH":
                switchNodeExecutor.execute(node, context, nodeErrors);
                break;
            default:
                throw new RuntimeException("未知的节点类型: " + nodeType);
        }

        NodeResult result = context.get(node.getNodeId());
        if (result != null && result.getHttpStatus() >= 400) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("nodeId", node.getNodeId());
            error.put("nodeName", node.getNodeName());
            error.put("httpStatus", result.getHttpStatus());
            if (result.getData() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = (Map<String, Object>) result.getData();
                error.put("message", dataMap.getOrDefault("error", "Unknown error"));
            }
            nodeErrors.add(error);
        }
    }
}
```

> 注意：`SwitchNodeExecutor.execute` 签名与 `NodeExecutor` 接口不同（多一个 `nodeErrors` 参数）。`SwitchNodeExecutor` **不实现** `NodeExecutor` 接口，由 `NodeRunner` 直接持有并调用。这样避免接口被 nodeErrors 污染，也避免 SWITCH 在收集错误时重复扫描自身。

- [ ] **步骤 2：编译（预期失败——`SwitchNodeExecutor` 尚未创建）**

运行：`mvn -q -DskipTests compile`
预期：编译失败，`cannot find symbol: SwitchNodeExecutor`。这是预期的，下一任务创建它。

---

## 任务 5：`SwitchNodeExecutor`

**文件：**
- 创建：`src/main/java/com/example/pipeline/node/SwitchNodeExecutor.java`

- [ ] **步骤 1：创建 `SwitchNodeExecutor`**

```java
package com.example.pipeline.node;

import com.example.pipeline.http.ExpressionException;
import com.example.pipeline.http.OutputScriptExecutor;
import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeDef;
import com.example.pipeline.model.SwitchCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SWITCH 节点执行器：按 cases 顺序评估 when（JS 表达式），
 * 命中第一个为 true 的 case 后顺序执行其子节点；
 * 全不命中则执行 defaultCase（若有）。
 * 子节点结果写入父 context，SWITCH 自身写入 matchedCase。
 */
@Component
public class SwitchNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(SwitchNodeExecutor.class);

    /** matchedCase 约定 */
    private static final int MATCHED_NONE = -1;
    private static final int MATCHED_DEFAULT = -2;

    @Resource
    private OutputScriptExecutor outputScriptExecutor;

    @Resource
    private NodeRunner nodeRunner;

    public void execute(NodeDef node, ExecutionContext context, List<Map<String, Object>> nodeErrors) {
        long start = System.currentTimeMillis();
        String nodeId = node.getNodeId();

        List<SwitchCase> cases = node.getCases();
        int matchedCase = MATCHED_NONE;
        boolean executed = false;

        if (cases != null) {
            for (int i = 0; i < cases.size(); i++) {
                SwitchCase switchCase = cases.get(i);
                String when = switchCase.getWhen();
                boolean hit;
                try {
                    hit = outputScriptExecutor.evaluateCondition(when, context);
                } catch (ExpressionException e) {
                    // when 异常/求值失败：视为不命中，继续下一个 case，不中断编排
                    log.warn("SWITCH 节点 {} case[{}] when 求值失败，跳过: expr=[{}] err={}",
                        nodeId, i, when, e.getMessage());
                    continue;
                }
                if (hit) {
                    matchedCase = i;
                    log.debug("SWITCH 节点 {} 命中 case[{}], expr=[{}]", nodeId, i, when);
                    nodeRunner.run(switchCase.getNodes(), context, nodeErrors);
                    executed = true;
                    break;
                }
            }
        }

        if (!executed) {
            List<NodeDef> defaultCase = node.getDefaultCase();
            if (defaultCase != null && !defaultCase.isEmpty()) {
                matchedCase = MATCHED_DEFAULT;
                log.debug("SWITCH 节点 {} 无 case 命中，执行 defaultCase", nodeId);
                nodeRunner.run(defaultCase, context, nodeErrors);
            } else {
                log.debug("SWITCH 节点 {} 无 case 命中且无 defaultCase，跳过", nodeId);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("matchedCase", matchedCase);
        long elapsedMs = System.currentTimeMillis() - start;
        context.put(nodeId, 200, data, elapsedMs);
        log.debug("SWITCH 节点 {} 完成, matchedCase={}, elapsed={}ms", nodeId, matchedCase, elapsedMs);
    }
}
```

- [ ] **步骤 2：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS（`NodeRunner` 引用的 `SwitchNodeExecutor` 已存在；二者循环依赖由 Spring 字段注入 + `@Resource` 自动处理，启动期验证留到集成测试）。

- [ ] **步骤 3：Commit**

```bash
git add src/main/java/com/example/pipeline/node/NodeRunner.java src/main/java/com/example/pipeline/node/SwitchNodeExecutor.java
git commit -m "feat(node): 新增 NodeRunner 与 SwitchNodeExecutor"
```

---

## 任务 6：`SwitchNodeExecutor` 单元测试

**文件：**
- 创建：`src/test/java/com/example/pipeline/node/SwitchNodeExecutorTest.java`

- [ ] **步骤 1：编写测试（mock OutputScriptExecutor + NodeRunner）**

```java
package com.example.pipeline.node;

import com.example.pipeline.http.OutputScriptExecutor;
import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeDef;
import com.example.pipeline.model.SwitchCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SwitchNodeExecutorTest {

    private OutputScriptExecutor outputScriptExecutor;
    private NodeRunner nodeRunner;
    private SwitchNodeExecutor executor;

    @BeforeEach
    void setUp() {
        outputScriptExecutor = mock(OutputScriptExecutor.class);
        nodeRunner = mock(NodeRunner.class);
        executor = new SwitchNodeExecutor();
        // 手动注入（避开 Spring）
        var outField = SwitchNodeExecutor.class.getDeclaredFields();
        try {
            java.lang.reflect.Field f1 = SwitchNodeExecutor.class.getDeclaredField("outputScriptExecutor");
            f1.setAccessible(true);
            f1.set(executor, outputScriptExecutor);
            java.lang.reflect.Field f2 = SwitchNodeExecutor.class.getDeclaredField("nodeRunner");
            f2.setAccessible(true);
            f2.set(executor, nodeRunner);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private NodeDef switchNode(List<SwitchCase> cases, List<NodeDef> defaultCase) {
        NodeDef node = new NodeDef();
        node.setNodeId("sw1");
        node.setNodeName("分支");
        node.setNodeType("SWITCH");
        node.setCases(cases);
        node.setDefaultCase(defaultCase);
        return node;
    }

    private SwitchCase aCase(String when, String... childIds) {
        SwitchCase c = new SwitchCase();
        c.setWhen(when);
        List<NodeDef> nodes = new ArrayList<>();
        for (String id : childIds) {
            NodeDef n = new NodeDef();
            n.setNodeId(id);
            n.setNodeType("SERIAL");
            nodes.add(n);
        }
        c.setNodes(nodes);
        return c;
    }

    @Test
    void 命中第一个case_只执行该case的子节点() {
        when(outputScriptExecutor.evaluateCondition(eq("c0"), any()))
            .thenReturn(true);

        NodeDef node = switchNode(
            List.of(aCase("c0", "n2"), aCase("c1", "n3")),
            List.of());

        executor.execute(node, new ExecutionContext(Map.of()), new ArrayList<>());

        // 只跑了 case0 的子节点
        ArgumentCaptor<List<NodeDef>> captor = @SuppressWarnings("unchecked")
            ArgumentCaptor.forClass(List.class);
        verify(nodeRunner, times(1)).run(captor.capture(), any(), any());
        assertThat(captor.getValue()).extracting(n -> n.getNodeId()).containsExactly("n2");

        // matchedCase=0
        assertThat(executor_resultMatchedCase()).isEqualTo(0);
    }

    @Test
    void 第一个不命中第二个命中_执行第二个case() {
        when(outputScriptExecutor.evaluateCondition(eq("c0"), any())).thenReturn(false);
        when(outputScriptExecutor.evaluateCondition(eq("c1"), any())).thenReturn(true);

        NodeDef node = switchNode(
            List.of(aCase("c0", "n2"), aCase("c1", "n3")),
            List.of());

        executor.execute(node, new ExecutionContext(Map.of()), new ArrayList<>());

        ArgumentCaptor<List<NodeDef>> captor = @SuppressWarnings("unchecked")
            ArgumentCaptor.forClass(List.class);
        verify(nodeRunner, times(1)).run(captor.capture(), any(), any());
        assertThat(captor.getValue()).extracting(n -> n.getNodeId()).containsExactly("n3");
        assertThat(executor_resultMatchedCase()).isEqualTo(1);
    }

    @Test
    void 全不命中_有default_执行default() {
        when(outputScriptExecutor.evaluateCondition(anyString(), any())).thenReturn(false);

        NodeDef node = switchNode(
            List.of(aCase("c0", "n2")),
            List.of(aCase("ignored", "ndef").getNodes().get(0).getNodeId().isEmpty() ? List.of() : List.of()));

        // 上面 default 构造有点绕，直接构造一个明确的 defaultCase
        NodeDef defNode = new NodeDef();
        defNode.setNodeId("ndef");
        defNode.setNodeType("SERIAL");
        node.setDefaultCase(List.of(defNode));

        executor.execute(node, new ExecutionContext(Map.of()), new ArrayList<>());

        ArgumentCaptor<List<NodeDef>> captor = @SuppressWarnings("unchecked")
            ArgumentCaptor.forClass(List.class);
        verify(nodeRunner, times(1)).run(captor.capture(), any(), any());
        assertThat(captor.getValue()).extracting(n -> n.getNodeId()).containsExactly("ndef");
        assertThat(executor_resultMatchedCase()).isEqualTo(-2);
    }

    @Test
    void 全不命中_无default_不执行子节点_matchedCase为负一() {
        when(outputScriptExecutor.evaluateCondition(anyString(), any())).thenReturn(false);

        NodeDef node = switchNode(List.of(aCase("c0", "n2")), null);

        executor.execute(node, new ExecutionContext(Map.of()), new ArrayList<>());

        verify(nodeRunner, never()).run(anyList(), any(), any());
        assertThat(executor_resultMatchedCase()).isEqualTo(-1);
    }

    @Test
    void when抛异常_该case跳过_继续评估后续() {
        when(outputScriptExecutor.evaluateCondition(eq("c0"), any()))
            .thenThrow(new com.example.pipeline.http.ExpressionException("boom"));
        when(outputScriptExecutor.evaluateCondition(eq("c1"), any()))
            .thenReturn(true);

        NodeDef node = switchNode(
            List.of(aCase("c0", "n2"), aCase("c1", "n3")),
            List.of());

        executor.execute(node, new ExecutionContext(Map.of()), new ArrayList<>());

        ArgumentCaptor<List<NodeDef>> captor = @SuppressWarnings("unchecked")
            ArgumentCaptor.forClass(List.class);
        verify(nodeRunner, times(1)).run(captor.capture(), any(), any());
        assertThat(captor.getValue()).extracting(n -> n.getNodeId()).containsExactly("n3");
        assertThat(executor_resultMatchedCase()).isEqualTo(1);
    }

    @Test
    void cases为空_无default_直接通过() {
        NodeDef node = switchNode(null, null);
        executor.execute(node, new ExecutionContext(Map.of()), new ArrayList<>());
        verify(nodeRunner, never()).run(anyList(), any(), any());
        assertThat(executor_resultMatchedCase()).isEqualTo(-1);
    }

    /**
     * 执行一次并从 context 读取 sw1 的 matchedCase。
     */
    private int executor_resultMatchedCase() {
        return lastMatchedCase;
    }

    private int lastMatchedCase = Integer.MIN_VALUE;

    // 覆盖 setUp 之外：每次 execute 后捕获 context 里的 matchedCase
    @BeforeEach
    void wireCapture() {
        // 用 around 方式不易；改为在各测试结尾直接断言 context。下面提供一个共享方法。
    }

    @Test
    void 命中case0_context写入matchedCase0() {
        when(outputScriptExecutor.evaluateCondition(eq("c0"), any())).thenReturn(true);
        ExecutionContext ctx = new ExecutionContext(Map.of());
        NodeDef node = switchNode(List.of(aCase("c0", "n2")), List.of());

        executor.execute(node, ctx, new ArrayList<>());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ctx.get("sw1").getData();
        assertThat(data.get("matchedCase")).isEqualTo(0);
        assertThat(ctx.get("sw1").getHttpStatus()).isEqualTo(200);
    }
}
```

> 说明：上面 `executor_resultMatchedCase()` 相关字段在重构中已基本被最后一个测试取代（直接断言 context）。为避免冗余/混淆，下面步骤 2 用精简后的最终版替换整个文件。

- [ ] **步骤 2：用精简最终版替换测试文件**

将 `SwitchNodeExecutorTest.java` 整体替换为：

```java
package com.example.pipeline.node;

import com.example.pipeline.http.ExpressionException;
import com.example.pipeline.http.OutputScriptExecutor;
import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeDef;
import com.example.pipeline.model.SwitchCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SwitchNodeExecutorTest {

    private OutputScriptExecutor outputScriptExecutor;
    private NodeRunner nodeRunner;
    private SwitchNodeExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        outputScriptExecutor = mock(OutputScriptExecutor.class);
        nodeRunner = mock(NodeRunner.class);
        executor = new SwitchNodeExecutor();
        setField(executor, "outputScriptExecutor", outputScriptExecutor);
        setField(executor, "nodeRunner", nodeRunner);
    }

    @Test
    void 命中第一个case_只执行该case的子节点_且context写入matchedCase0() {
        when(outputScriptExecutor.evaluateCondition(eq("c0"), any())).thenReturn(true);
        ExecutionContext ctx = new ExecutionContext(Map.of());
        NodeDef node = switchNode(List.of(aCase("c0", "n2"), aCase("c1", "n3")), List.of());

        executor.execute(node, ctx, new ArrayList<>());

        List<NodeDef> run = captureSingleRun();
        assertThat(run).extracting(NodeDef::getNodeId).containsExactly("n2");
        assertMatchedCase(ctx, 0);
        assertThat(ctx.get("sw1").getHttpStatus()).isEqualTo(200);
    }

    @Test
    void 第一个不命中第二个命中_执行第二个case() {
        when(outputScriptExecutor.evaluateCondition(eq("c0"), any())).thenReturn(false);
        when(outputScriptExecutor.evaluateCondition(eq("c1"), any())).thenReturn(true);
        ExecutionContext ctx = new ExecutionContext(Map.of());
        NodeDef node = switchNode(List.of(aCase("c0", "n2"), aCase("c1", "n3")), List.of());

        executor.execute(node, ctx, new ArrayList<>());

        List<NodeDef> run = captureSingleRun();
        assertThat(run).extracting(NodeDef::getNodeId).containsExactly("n3");
        assertMatchedCase(ctx, 1);
    }

    @Test
    void 全不命中_有default_执行default_匹配为负二() {
        when(outputScriptExecutor.evaluateCondition(anyString(), any())).thenReturn(false);
        ExecutionContext ctx = new ExecutionContext(Map.of());
        NodeDef node = switchNode(List.of(aCase("c0", "n2")), List.of(serialNode("ndef")));

        executor.execute(node, ctx, new ArrayList<>());

        List<NodeDef> run = captureSingleRun();
        assertThat(run).extracting(NodeDef::getNodeId).containsExactly("ndef");
        assertMatchedCase(ctx, -2);
    }

    @Test
    void 全不命中_无default_不执行子节点_匹配为负一() {
        when(outputScriptExecutor.evaluateCondition(anyString(), any())).thenReturn(false);
        ExecutionContext ctx = new ExecutionContext(Map.of());
        NodeDef node = switchNode(List.of(aCase("c0", "n2")), null);

        executor.execute(node, ctx, new ArrayList<>());

        verify(nodeRunner, never()).run(anyList(), any(), any());
        assertMatchedCase(ctx, -1);
    }

    @Test
    void when抛异常_该case跳过_继续评估后续case() {
        when(outputScriptExecutor.evaluateCondition(eq("c0"), any()))
            .thenThrow(new ExpressionException("boom"));
        when(outputScriptExecutor.evaluateCondition(eq("c1"), any())).thenReturn(true);
        ExecutionContext ctx = new ExecutionContext(Map.of());
        NodeDef node = switchNode(List.of(aCase("c0", "n2"), aCase("c1", "n3")), List.of());

        executor.execute(node, ctx, new ArrayList<>());

        List<NodeDef> run = captureSingleRun();
        assertThat(run).extracting(NodeDef::getNodeId).containsExactly("n3");
        assertMatchedCase(ctx, 1);
    }

    @Test
    void cases为空_无default_直接通过_匹配为负一() {
        ExecutionContext ctx = new ExecutionContext(Map.of());
        NodeDef node = switchNode(null, null);

        executor.execute(node, ctx, new ArrayList<>());

        verify(nodeRunner, never()).run(anyList(), any(), any());
        assertMatchedCase(ctx, -1);
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private List<NodeDef> captureSingleRun() {
        ArgumentCaptor<List<NodeDef>> captor = ArgumentCaptor.forClass(List.class);
        verify(nodeRunner, times(1)).run(captor.capture(), any(), any());
        return captor.getValue();
    }

    private void assertMatchedCase(ExecutionContext ctx, int expected) {
        assertThat(ctx.get("sw1")).as("sw1 应写入 NodeResult").isNotNull();
        Map<String, Object> data = (Map<String, Object>) ctx.get("sw1").getData();
        assertThat(data.get("matchedCase")).isEqualTo(expected);
    }

    private NodeDef switchNode(List<SwitchCase> cases, List<NodeDef> defaultCase) {
        NodeDef node = new NodeDef();
        node.setNodeId("sw1");
        node.setNodeName("分支");
        node.setNodeType("SWITCH");
        node.setCases(cases);
        node.setDefaultCase(defaultCase);
        return node;
    }

    private SwitchCase aCase(String when, String... childIds) {
        SwitchCase c = new SwitchCase();
        c.setWhen(when);
        List<NodeDef> nodes = new ArrayList<>();
        for (String id : childIds) {
            nodes.add(serialNode(id));
        }
        c.setNodes(nodes);
        return c;
    }

    private NodeDef serialNode(String id) {
        NodeDef n = new NodeDef();
        n.setNodeId(id);
        n.setNodeType("SERIAL");
        return n;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
```

- [ ] **步骤 3：运行测试验证通过**

运行：`mvn -q test -Dtest=SwitchNodeExecutorTest`
预期：6 个测试全部 PASS。

- [ ] **步骤 4：Commit**

```bash
git add src/test/java/com/example/pipeline/node/SwitchNodeExecutorTest.java
git commit -m "test(node): SwitchNodeExecutor 行为单元测试"
```

---

## 任务 7：`NodeRunner` 单元测试（含嵌套 SWITCH）

**文件：**
- 创建：`src/test/java/com/example/pipeline/node/NodeRunnerTest.java`

- [ ] **步骤 1：编写测试**

```java
package com.example.pipeline.node;

import com.example.pipeline.model.ExecutionContext;
import com.example.pipeline.model.NodeDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NodeRunner 的分发与错误收集逻辑用 mock 的子执行器验证，
 * 不依赖真实 SERIAL/FORK/ITERATE/SWITCH 执行器。
 * 通过反射注入 mock 子执行器。
 */
class NodeRunnerTest {

    private NodeRunner runner;
    private SerialNodeExecutor serial;
    private ForkNodeExecutor fork;
    private IterateNodeExecutor iterate;
    private SwitchNodeExecutor sw;

    @BeforeEach
    void setUp() throws Exception {
        runner = new NodeRunner();
        serial = mock(SerialNodeExecutor.class);
        fork = mock(ForkNodeExecutor.class);
        iterate = mock(IterateNodeExecutor.class);
        sw = mock(SwitchNodeExecutor.class);
        setField(runner, "serialNodeExecutor", serial);
        setField(runner, "forkNodeExecutor", fork);
        setField(runner, "iterateNodeExecutor", iterate);
        setField(runner, "switchNodeExecutor", sw);
    }

    @Test
    void run空列表_不调用任何执行器() {
        runner.run(null, new ExecutionContext(Map.of()), new ArrayList<>());
        verifyNoInteractions(serial, fork, iterate, sw);
    }

    @Test
    void 按类型分发_SERIAL_FORK_ITERATE_SWITCH() {
        ExecutionContext ctx = new ExecutionContext(Map.of());
        List<NodeDef> nodes = List.of(
            node("s1", "SERIAL"),
            node("f1", "FORK"),
            node("i1", "ITERATE"),
            node("sw1", "SWITCH"));

        runner.run(nodes, ctx, new ArrayList<>());

        verify(serial, times(1)).execute(eq(nodes.get(0)), eq(ctx));
        verify(fork, times(1)).execute(eq(nodes.get(1)), eq(ctx));
        verify(iterate, times(1)).execute(eq(nodes.get(2)), eq(ctx));
        verify(sw, times(1)).eq(eq(nodes.get(3)), eq(ctx));
    }

    @Test
    void SWITCH分发携带nodeErrors参数_其他类型不带() {
        ExecutionContext ctx = new ExecutionContext(Map.of());
        List<NodeDef> nodes = List.of(node("s1", "SERIAL"), node("sw1", "SWITCH"));
        List<Map<String, Object>> errors = new ArrayList<>();

        runner.run(nodes, ctx, errors);

        verify(serial).execute(any(), any());              // 二参
        verify(sw).execute(any(), any(), eq(errors));      // 三参含 errors
    }

    @Test
    void 未知节点类型_抛异常() {
        NodeDef bad = node("x", "WAT");
        try {
            runner.run(List.of(bad), new ExecutionContext(Map.of()), new ArrayList<>());
            org.assertj.core.api.Assertions.fail("应抛异常");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("未知的节点类型").contains("WAT");
        }
    }

    @Test
    void httpStatus大于等于400_收集到nodeErrors() {
        ExecutionContext ctx = new ExecutionContext(Map.of());
        // 让 serial 执行后往 ctx 写一个 500 结果
        doAnswer(inv -> {
            ctx.put("s1", 500, Map.of("error", "boom"), 10);
            return null;
        }).when(serial).execute(any(), any());

        List<Map<String, Object>> errors = new ArrayList<>();
        runner.run(List.of(node("s1", "SERIAL")), ctx, errors);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("nodeId", "s1");
        assertThat(errors.get(0)).containsEntry("httpStatus", 500);
        assertThat(errors.get(0)).containsEntry("message", "boom");
    }

    private NodeDef node(String id, String type) {
        NodeDef n = new NodeDef();
        n.setNodeId(id);
        n.setNodeName(id);
        n.setNodeType(type);
        return n;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
```

> 注意步骤 1 中 `verify(sw).eq(...)` 是笔误，应为 `verify(sw).execute(...)`。下方步骤 2 给出修正版。

- [ ] **步骤 2：修正 `verify(sw)` 笔误**

将测试方法 `按类型分发_SERIAL_FORK_ITERATE_SWITCH` 中：

```java
        verify(sw, times(1)).eq(eq(nodes.get(3)), eq(ctx));
```

改为：

```java
        verify(sw, times(1)).execute(eq(nodes.get(3)), eq(ctx), anyList());
```

（`SwitchNodeExecutor.execute` 是三参方法，需匹配三参；`anyList()` 占位 nodeErrors。）

- [ ] **步骤 3：运行测试验证通过**

运行：`mvn -q test -Dtest=NodeRunnerTest`
预期：5 个测试全部 PASS。

- [ ] **步骤 4：Commit**

```bash
git add src/test/java/com/example/pipeline/node/NodeRunnerTest.java
git commit -m "test(node): NodeRunner 分发与错误收集测试"
```

---

## 任务 8：`PipelineExecutor` 改用 `NodeRunner`

**文件：**
- 修改：`src/main/java/com/example/pipeline/service/PipelineExecutor.java`

- [ ] **步骤 1：注入 `NodeRunner`，顶层循环改为调 `NodeRunner.run`，删除内联 `executeNode`**

在 `PipelineExecutor.java` 中：

(a) 在字段区（`@Resource private IterateNodeExecutor iterateNodeExecutor;` 之后）新增：

```java
    @Resource
    private com.example.pipeline.node.NodeRunner nodeRunner;
```

(b) 将 `execute()` 方法中的顶层循环：

```java
        List<Map<String, Object>> nodeErrors = new ArrayList<>();
        try {
            for (NodeDef node : config.getNodes()) {
                executeNode(node, context, nodeErrors);
            }
        } catch (ExpressionException e) {
            log.error("表达式解析失败，终止编排: {}", e.getMessage());
            return PipelineExecResponse.error("表达式解析失败: " + e.getMessage());
        }
```

替换为：

```java
        List<Map<String, Object>> nodeErrors = new ArrayList<>();
        try {
            nodeRunner.run(config.getNodes(), context, nodeErrors);
        } catch (ExpressionException e) {
            log.error("表达式解析失败，终止编排: {}", e.getMessage());
            return PipelineExecResponse.error("表达式解析失败: " + e.getMessage());
        }
```

(c) 删除整个旧的 `private void executeNode(NodeDef node, ExecutionContext context, List<Map<String, Object>> nodeErrors)` 方法（其逻辑已搬到 `NodeRunner`）。

(d) 删除现在不再使用的字段注入（`serialNodeExecutor`、`forkNodeExecutor`、`iterateNodeExecutor`）以及对应 import，避免未使用警告。删除：

```java
    @Resource
    private SerialNodeExecutor serialNodeExecutor;

    @Resource
    private ForkNodeExecutor forkNodeExecutor;

    @Resource
    private IterateNodeExecutor iterateNodeExecutor;
```

及 import：

```java
import com.example.pipeline.node.ForkNodeExecutor;
import com.example.pipeline.node.IterateNodeExecutor;
import com.example.pipeline.node.SerialNodeExecutor;
```

（保留 `ExpressionResolver`、`OutputScriptExecutor` 注入，outputMapping/outputScript 仍用。）

- [ ] **步骤 2：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS。

- [ ] **步骤 3：运行已有测试确保未回归**

运行：`mvn -q test -Dtest=SwitchNodeExecutorTest,NodeRunnerTest`
预期：全部 PASS。

- [ ] **步骤 4：Commit**

```bash
git add src/main/java/com/example/pipeline/service/PipelineExecutor.java
git commit -m "refactor(service): PipelineExecutor 顶层执行改用 NodeRunner"
```

---

## 任务 9：`IterateNodeExecutor` 子节点改用 `NodeRunner`

**文件：**
- 修改：`src/main/java/com/example/pipeline/node/IterateNodeExecutor.java`

- [ ] **步骤 1：注入 `NodeRunner`，`executeSubPipeline` 改为调 `nodeRunner.run`**

(a) 新增字段（替换现有 `@Resource private SerialNodeExecutor serialNodeExecutor;`）：

```java
    @Resource
    private NodeRunner nodeRunner;
```

删除 `import` 中不再单独使用的 `SerialNodeExecutor`（若文件 import 为 `com.example.pipeline.node.*` 通配则无需改；本文件第 1-13 行确认是通配 `import com.example.pipeline.model.*;` 和具名 import。检查：第 3 行 `import com.example.pipeline.http.ExpressionException;` 等。`SerialNodeExecutor` 因同包无需 import，删除字段即可）。

(b) 将 `executeSubPipeline` 中：

```java
        // 按序执行子编排中的每个节点
        for (NodeDef subNode : subNodes) {
            try {
                serialNodeExecutor.execute(subNode, subContext);
            } catch (Exception e) {
                log.error("ITERATE 子节点 {} 执行失败: {}", subNode.getNodeId(), e.getMessage());
            }
        }
```

替换为：

```java
        // 按序执行子编排中的每个节点（支持 SERIAL/FORK/ITERATE/SWITCH 嵌套）
        try {
            nodeRunner.run(subNodes, subContext, new ArrayList<>());
        } catch (Exception e) {
            log.error("ITERATE 子编排执行失败: {}", e.getMessage());
        }
```

并补 import：`import java.util.ArrayList;`（文件已有 `import java.util.*;`，无需新增）。

- [ ] **步骤 2：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS。

- [ ] **步骤 3：运行全部测试**

运行：`mvn -q test -Dtest=SwitchNodeExecutorTest,NodeRunnerTest`
预期：全部 PASS（IterateNodeExecutor 本身无单测，行为由集成案例验证）。

- [ ] **步骤 4：Commit**

```bash
git add src/main/java/com/example/pipeline/node/IterateNodeExecutor.java
git commit -m "refactor(node): ITERATE 子节点改用 NodeRunner 支持嵌套 SWITCH/FORK/ITERATE"
```

---

## 任务 10：新增 mock 端点

**文件：**
- 修改：`src/main/java/com/example/pipeline/controller/MockApiController.java`

- [ ] **步骤 1：新增 3 个端点**

在 `MockApiController` 类中（`queryUserDetail` 方法之后）追加：

```java
    @PostMapping("/user/type")
    public Map<String, Object> queryUserType(@RequestBody Map<String, Object> params) {
        Object id = params != null ? params.get("id") : null;
        String idStr = id != null ? String.valueOf(id) : "0";
        // id 末位偶数 → VIP，奇数 → NORMAL
        char lastChar = idStr.charAt(idStr.length() - 1);
        String type = ((lastChar - '0') % 2 == 0) ? "VIP" : "NORMAL";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userid", id != null ? "user-" + id : "user-default");
        data.put("type", type);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", "200");
        response.put("data", data);
        response.put("msg", "success");
        return response;
    }

    @PostMapping("/vip/discount")
    public Map<String, Object> vipDiscount(@RequestBody Map<String, Object> params) {
        Object userid = params != null ? params.get("userid") : null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userid", userid);
        data.put("level", "VIP");
        data.put("discount", 0.7);
        data.put("benefit", "专属客服 + 生日礼");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", "200");
        response.put("data", data);
        response.put("msg", "success");
        return response;
    }

    @PostMapping("/normal/info")
    public Map<String, Object> normalInfo(@RequestBody Map<String, Object> params) {
        Object userid = params != null ? params.get("userid") : null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userid", userid);
        data.put("level", "NORMAL");
        data.put("points", 100);
        data.put("tip", "消费累计积分可升级");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", "200");
        response.put("data", data);
        response.put("msg", "success");
        return response;
    }
```

- [ ] **步骤 2：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS。

- [ ] **步骤 3：Commit**

```bash
git add src/main/java/com/example/pipeline/controller/MockApiController.java
git commit -m "feat(mock): 新增 user/type、vip/discount、normal/info 端点"
```

---

## 任务 11：`DemoDataInitializer` 注册 API + 示例编排

**文件：**
- 修改：`src/main/java/com/example/pipeline/config/DemoDataInitializer.java`

- [ ] **步骤 1：在 `initDemoData` 追加注册与编排**

(a) 将 `initDemoData` 方法改为：

```java
    @EventListener(ApplicationReadyEvent.class)
    public void initDemoData() {
        upsertApi("获取用户ID", "GET_USER_ID", "/mock-api/user/query", "本地测试 API：根据 id 返回 userid");
        upsertApi("获取用户详情", "GET_USER_DETAIL", "/mock-api/user/detail", "本地测试 API：根据 userid 返回用户详情");
        upsertApi("获取用户类型", "GET_USER_TYPE", "/mock-api/user/type", "本地测试 API：根据 id 返回 userid 与 type(VIP/NORMAL)");
        upsertApi("VIP折扣", "GET_VIP_DISCOUNT", "/mock-api/vip/discount", "本地测试 API：VIP 专属折扣");
        upsertApi("普通用户信息", "GET_NORMAL_INFO", "/mock-api/normal/info", "本地测试 API：普通用户信息");
        upsertPipeline();
        upsertSwitchPipeline();
    }
```

(b) 在类末尾（`outputScript()` 方法之后）追加：

```java
    private void upsertSwitchPipeline() {
        PipelineDef pipeline = pipelineDefMapper.findByCode("USER_TYPE_SWITCH");
        if (pipeline == null) {
            pipeline = new PipelineDef();
            pipeline.setPipelineCode("USER_TYPE_SWITCH");
        }
        pipeline.setPipelineName("用户类型分流");
        pipeline.setPipelineConfig(buildSwitchPipelineConfigJson());
        pipeline.setDescription("条件分支示例：id -> 用户类型 -> VIP走折扣 / 普通走信息");
        pipeline.setStatus(1);

        if (pipeline.getId() == null) {
            pipelineDefMapper.insert(pipeline);
        } else {
            pipelineDefMapper.update(pipeline);
        }
        log.info("USER_TYPE_SWITCH 条件分支编排已初始化");
    }

    private String buildSwitchPipelineConfigJson() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("inputParams", List.of(inputParam()));
        config.put("nodes", List.of(getUserTypeNode(), getSwitchNode()));
        config.put("outputScript", switchOutputScript());
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("初始化 SWITCH 编排 JSON 失败", e);
        }
    }

    private Map<String, Object> getUserTypeNode() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("nodeId", "n1");
        node.put("nodeName", "获取用户类型");
        node.put("nodeType", "SERIAL");
        node.put("apiCode", "GET_USER_TYPE");
        node.put("inputMapping", Map.of("id", "${request.id}"));
        return node;
    }

    private Map<String, Object> getSwitchNode() {
        // case 0: VIP -> n2 GET_VIP_DISCOUNT
        Map<String, Object> vipNode = new LinkedHashMap<>();
        vipNode.put("nodeId", "n2");
        vipNode.put("nodeName", "VIP折扣");
        vipNode.put("nodeType", "SERIAL");
        vipNode.put("apiCode", "GET_VIP_DISCOUNT");
        vipNode.put("inputMapping", Map.of("userid", "${n1.data.data.userid}"));

        Map<String, Object> vipCase = new LinkedHashMap<>();
        vipCase.put("when", "n1.data.data.type === 'VIP'");
        vipCase.put("nodes", List.of(vipNode));

        // case 1: NORMAL -> n3 GET_NORMAL_INFO
        Map<String, Object> normalNode = new LinkedHashMap<>();
        normalNode.put("nodeId", "n3");
        normalNode.put("nodeName", "普通用户信息");
        normalNode.put("nodeType", "SERIAL");
        normalNode.put("apiCode", "GET_NORMAL_INFO");
        normalNode.put("inputMapping", Map.of("userid", "${n1.data.data.userid}"));

        Map<String, Object> normalCase = new LinkedHashMap<>();
        normalCase.put("when", "n1.data.data.type === 'NORMAL'");
        normalCase.put("nodes", List.of(normalNode));

        Map<String, Object> switchNode = new LinkedHashMap<>();
        switchNode.put("nodeId", "sw1");
        switchNode.put("nodeName", "根据用户类型分流");
        switchNode.put("nodeType", "SWITCH");
        switchNode.put("cases", List.of(vipCase, normalCase));
        switchNode.put("defaultCase", List.of());
        return switchNode;
    }

    private String switchOutputScript() {
        return "return {\n"
            + "  userId: n1.data.data.userid,\n"
            + "  userType: n1.data.data.type,\n"
            + "  branch: (typeof n2 !== 'undefined' && n2) ? 'VIP' : 'NORMAL',\n"
            + "  detail: (typeof n2 !== 'undefined' && n2) ? n2.data.data : (n3 ? n3.data.data : null),\n"
            + "  matchedCase: sw1.data.matchedCase\n"
            + "};";
    }
```

- [ ] **步骤 2：编译验证**

运行：`mvn -q -DskipTests compile`
预期：BUILD SUCCESS。

- [ ] **步骤 3：Commit**

```bash
git add src/main/java/com/example/pipeline/config/DemoDataInitializer.java
git commit -m "feat(demo): 注册条件分支示例编排 USER_TYPE_SWITCH"
```

---

## 任务 12：UI 表单支持 SWITCH

**文件：**
- 修改：`src/main/resources/templates/pipeline/form.html`

- [ ] **步骤 1：节点类型按钮区加 `+ 条件分支节点`**

将 `form.html` 第 43-47 行的按钮区：

```html
                <div>
                    <button type="button" class="btn btn-sm btn-outline-primary" onclick="addNode('SERIAL')">+ 串行节点</button>
                    <button type="button" class="btn btn-sm btn-outline-info" onclick="addNode('FORK')">+ 并行节点</button>
                    <button type="button" class="btn btn-sm btn-outline-warning" onclick="addNode('ITERATE')">+ 循环节点</button>
                </div>
```

替换为：

```html
                <div>
                    <button type="button" class="btn btn-sm btn-outline-primary" onclick="addNode('SERIAL')">+ 串行节点</button>
                    <button type="button" class="btn btn-sm btn-outline-info" onclick="addNode('FORK')">+ 并行节点</button>
                    <button type="button" class="btn btn-sm btn-outline-warning" onclick="addNode('ITERATE')">+ 循环节点</button>
                    <button type="button" class="btn btn-sm btn-outline-secondary" onclick="addNode('SWITCH')">+ 条件分支节点</button>
                </div>
```

- [ ] **步骤 2：`addNode` 增加 SWITCH 分支**

在 `form.html` 的 `addNode` 函数中，`else if (type === 'ITERATE') { ... }` 块之后、`document.getElementById('nodesContainer').insertAdjacentHTML('beforeend', nodeHtml);` 之前，插入 SWITCH 分支：

```javascript
    } else if (type === 'SWITCH') {
        nodeHtml = `
        <div class="card mb-2 node-card" data-node-idx="${idx}" data-node-type="SWITCH">
            <div class="card-body">
                <div class="row mb-2">
                    <div class="col-md-2">
                        <input type="text" class="form-control" placeholder="节点ID" name="nodeId_${idx}" value="${escapeHtml(node.nodeId || `sw${idx+1}`)}">
                    </div>
                    <div class="col-md-3">
                        <input type="text" class="form-control" placeholder="节点名称" name="nodeName_${idx}" value="${escapeHtml(node.nodeName)}">
                    </div>
                    <div class="col-md-2">
                        <span class="badge bg-secondary">SWITCH</span>
                    </div>
                    <div class="col-md-4">
                        <small class="text-muted">分支条件（when）与各 case 子节点请在下方"完整编排配置 JSON"中配置</small>
                    </div>
                    <div class="col-md-1">
                        <button type="button" class="btn btn-sm btn-outline-danger" onclick="this.closest('.node-card').remove()">×</button>
                    </div>
                </div>
            </div>
        </div>`;
    }
```

- [ ] **步骤 3：提交逻辑中 SWITCH 沿用 `dataset.originalNode` 回带**

在表单 submit 处理器的节点收集逻辑（`document.querySelectorAll('.node-card').forEach` 内），现有：

```javascript
        } else if (type === 'FORK') {
            const existing = card.dataset.originalNode ? JSON.parse(card.dataset.originalNode) : {};
            node.tasks = existing.tasks || [];
        }
```

之后追加：

```javascript
        } else if (type === 'SWITCH') {
            const existing = card.dataset.originalNode ? JSON.parse(card.dataset.originalNode) : {};
            node.cases = existing.cases || [];
            node.defaultCase = existing.defaultCase || [];
        }
```

- [ ] **步骤 4：配置预览渲染 SWITCH**

在 `renderConfigPreview` 的 `nodeRows` 中，现有：

```javascript
                <td><pre class="mb-0 small">${escapeHtml(formatJson(node.inputMapping || node.tasks || node.subPipeline || {}))}</pre></td>
```

替换为：

```javascript
                <td><pre class="mb-0 small">${escapeHtml(formatJson(node.inputMapping || node.tasks || node.subPipeline || node.cases || {}))}</pre></td>
```

- [ ] **步骤 5：启动应用手动验证 UI**

启动应用（MySQL+Redis 已运行）：

```bash
mvn -q -DskipTests spring-boot:run > /tmp/pipeline-app.log 2>&1 &
```

等待就绪后访问 http://localhost:8080/page/pipeline ，点击"新增"，在节点编排区应看到 `+ 条件分支节点` 按钮；点击后出现 SWITCH 卡片。

访问 http://localhost:8080/page/pipeline/USER_TYPE_SWITCH/edit （若该编排已由 DemoDataInitializer 写入）应能在预览区看到 sw1 节点及其 cases 配置。

预期：UI 正常渲染，无 JS 报错（浏览器控制台干净）。

- [ ] **步骤 6：Commit**

```bash
git add src/main/resources/templates/pipeline/form.html
git commit -m "feat(ui): pipeline 表单支持 SWITCH 节点"
```

---

## 任务 13：集成冒烟验证

**文件：** 无（运行验证）

- [ ] **步骤 1：构建并启动**

确保 MySQL（root/weeha, pipeline_platform 库）与 Redis 已运行。打包：

```bash
mvn -q -DskipTests package
```
预期：BUILD SUCCESS。

启动（若前序任务已启动则重启以加载新代码）：

```bash
pkill -f 'spring-boot:run' 2>/dev/null; sleep 2
mvn -q -DskipTests spring-boot:run > /tmp/pipeline-app.log 2>&1 &
```

等待就绪：

```bash
for i in $(seq 1 60); do curl -sf -o /dev/null http://localhost:8080/page/pipeline && break; sleep 1; done
```

- [ ] **步骤 2：验证 VIP 分支（id 末位偶数，如 1002）**

运行：
```bash
curl -s -X POST http://localhost:8080/api/pipeline/USER_TYPE_SWITCH/execute \
  -H 'Content-Type: application/json' -d '{"id":"1002"}'
```
预期：HTTP 200，响应 JSON 中
- `userType` = `"VIP"`
- `branch` = `"VIP"`
- `detail.level` = `"VIP"`、`detail.discount` = `0.7`（来自 n2 GET_VIP_DISCOUNT）
- `matchedCase` = `0`

- [ ] **步骤 3：验证 NORMAL 分支（id 末位奇数，如 1001）**

运行：
```bash
curl -s -X POST http://localhost:8080/api/pipeline/USER_TYPE_SWITCH/execute \
  -H 'Content-Type: application/json' -d '{"id":"1001"}'
```
预期：HTTP 200，响应 JSON 中
- `userType` = `"NORMAL"`
- `branch` = `"NORMAL"`
- `detail.level` = `"NORMAL"`、`detail.points` = `100`（来自 n3 GET_NORMAL_INFO）
- `matchedCase` = `1`

- [ ] **步骤 4：回归验证原有 USER_CHAIN 编排未受影响**

运行：
```bash
curl -s -X POST http://localhost:8080/api/pipeline/USER_CHAIN/execute \
  -H 'Content-Type: application/json' -d '{"id":"1001"}'
```
预期：HTTP 200，与改造前一致（`displayName` 等字段正常，`source: outputScript`）。

- [ ] **步骤 5：全部单元测试最终回归**

运行：
```bash
mvn -q test
```
预期：SwitchNodeExecutorTest、NodeRunnerTest 全部 PASS，BUILD SUCCESS。

- [ ] **步骤 6：Commit（如有日志/验证脚本产出则忽略，无源码改动则跳过）**

无源码改动，跳过 commit。

---

## 自检

**1. 规格覆盖度**
- §3 配置模型（SwitchCase、NodeDef 字段）→ 任务 1、2 ✓
- §4 执行语义（第一个命中、defaultCase、matchedCase、嵌套）→ 任务 5、6、7 ✓
- §4.1 matchedCase 取值（0/-1/-2）→ 任务 5 实现 + 任务 6 测试 ✓
- §5 NodeRunner 重构 → 任务 4、8、9 ✓
- §5 OutputScriptExecutor 抽出 prepareBindings/evaluateCondition → 任务 3 ✓
- §5 IterateNodeExecutor 改用 NodeRunner → 任务 9 ✓
- §6 错误处理（when 异常跳过、子节点失败不中断、cases 空）→ 任务 5 实现 + 任务 6 测试 ✓
- §7 mock 端点 + API 注册 + USER_TYPE_SWITCH → 任务 10、11 ✓
- §7.4 验证（VIP/NORMAL 两种分支）→ 任务 13 ✓
- §8 测试（SwitchNodeExecutorTest、NodeRunnerTest）→ 任务 6、7 ✓
- UI 接入 → 任务 12 ✓

**2. 占位符扫描**：无 TODO/待定；每个代码步骤含完整代码块。

**3. 类型一致性**
- `SwitchCase.getWhen()` / `getNodes()` 在任务 1 定义，任务 5、6 使用一致 ✓
- `NodeDef.getCases()` / `getDefaultCase()` 任务 2 定义，任务 5、6、12 使用一致 ✓
- `NodeRunner.run(List<NodeDef>, ExecutionContext, List<Map<String,Object>>)` 签名在任务 4 定义，任务 5、8、9 调用一致 ✓
- `SwitchNodeExecutor.execute(NodeDef, ExecutionContext, List<Map<String,Object>>)` 三参签名在任务 5 定义，任务 4、7、8 调用一致 ✓
- `OutputScriptExecutor.evaluateCondition(String, ExecutionContext)` 在任务 3 定义，任务 5 调用一致 ✓
- `matchedCase` 常量 0/-1/-2 在任务 5 实现，任务 6 测试断言一致 ✓
- 表达式路径 `n1.data.data.type` / `n1.data.data.userid` 在任务 10（mock 返回结构）、任务 11（编排配置）一致 ✓

**4. 模糊性**：子节点 context 隔离规则（SWITCH 用父 context、ITERATE 用 fork）在"数据流与上下文约定"明示 ✓
