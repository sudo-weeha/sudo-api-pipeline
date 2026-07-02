# 条件分支（SWITCH 节点）设计

- 日期：2026-07-01
- 状态：已确认，待编写实现计划

## 1. 背景与目标

现有编排引擎支持三种节点类型：SERIAL（串行调用）、FORK（并行）、ITERATE（循环）。
节点按 `config.nodes` 扁平顺序执行，`ExpressionResolver` 仅支持 `${...}` 路径取值，
**不支持**比较 / 逻辑运算，因此无法表达"根据接口 1 的返回值决定走接口 2 还是接口 3"。

本设计新增 **SWITCH 节点**：基于 Nashorn JS 表达式评估多个互斥分支，
命中第一个为 true 的 case，顺序执行其子节点；支持 defaultCase，支持嵌套（SWITCH 内可含任意节点类型，含 SWITCH 自身）。

## 2. 关键决策（已与用户确认）

| 决策点 | 选择 |
|---|---|
| 分支结构 | **SWITCH 节点**（非节点级 `when` 守卫、非 IF/ELSE） |
| 条件求值机制 | **复用 Nashorn JS**（`when` 是 JS 表达式，绑定 n1/n2…/request/itemAlias 为 JS 变量，与 `OutputScriptExecutor` 完全一致） |
| 多 case 命中规则 | **只执行第一个命中的 case**，其余跳过 |
| case 内执行模型 | 子节点写进**同一个 ExecutionContext**，用各自 nodeId 存结果，复用现有各 NodeExecutor |
| 条件写法首版 | **纯 JS `when`**，不支持 `switchOn`+`equals` 简写（YAGNI） |
| SWITCH 是否留 NodeResult | **留一条**，记录命中 case 索引 |
| UI 范围 | SWITCH 接入现有"表单卡片 + 底部完整 JSON 编辑子节点"模式，**不做独立 case 可视化编辑器** |
| 数据库示例 | 新增 `USER_TYPE_SWITCH` 编排 + 3 个 mock 端点 + 3 个 API 注册 |
| 重构方式 | 抽出独立 `NodeRunner` 组件统一"顺序执行一组 nodes"，避免循环依赖、支持任意嵌套 |

## 3. 配置模型

### 3.1 新增 `SwitchCase`

```java
@Data
public class SwitchCase {
    private String when;            // JS 表达式，求值为 true 则命中此 case
    private List<NodeDef> nodes;    // 命中后顺序执行的子节点
}
```

### 3.2 `NodeDef` 新增字段

```java
private List<SwitchCase> cases;        // SWITCH 节点使用
private List<NodeDef> defaultCase;     // SWITCH 节点使用（可选）
```

### 3.3 配置示例

```json
{
  "nodeId": "sw1",
  "nodeName": "根据用户类型分流",
  "nodeType": "SWITCH",
  "cases": [
    {
      "when": "n1.data.type === 'VIP'",
      "nodes": [
        { "nodeId": "n2", "nodeName": "调用VIP接口", "nodeType": "SERIAL",
          "apiCode": "GET_VIP_DISCOUNT", "inputMapping": {"userid": "${n1.data.userid}"} }
      ]
    },
    {
      "when": "n1.data.type === 'NORMAL'",
      "nodes": [
        { "nodeId": "n3", "nodeName": "调用普通接口", "nodeType": "SERIAL",
          "apiCode": "GET_NORMAL_INFO", "inputMapping": {"userid": "${n1.data.userid}"} }
      ]
    }
  ],
  "defaultCase": []
}
```

## 4. 执行语义

1. 进入 SWITCH 节点，按 `cases` 数组顺序逐个求值 `when`（JS 表达式，复用 Nashorn）。
2. **第一个求值为 true 的 case 命中**，顺序执行其 `nodes`；命中后不再评估后续 case。
3. 命中的子节点写进同一个 ExecutionContext，用各自 nodeId 存结果；后续节点 / outputScript / outputMapping 用 `${n2.data}` 等正常引用。
4. 子节点支持任意类型（SERIAL / FORK / ITERATE / 嵌套 SWITCH），失败处理沿用全局规则（httpStatus≥400 记 nodeErrors，不中断）。
5. 若所有 case 都不命中：
   - `defaultCase` 非空 → 执行 `defaultCase`；
   - `defaultCase` 为空或缺省 → 什么都不做，SWITCH 视为正常通过。
6. SWITCH 节点自身在 context 写一条 `NodeResult`：`httpStatus=200`，`data={"matchedCase": <值>}`。

### 4.1 `matchedCase` 取值约定

| 情况 | matchedCase |
|---|---|
| 命中 cases[0] | 0 |
| 命中 cases[1] | 1 |
| … | … |
| 命中 defaultCase | -2 |
| 无 case 命中且无 defaultCase | -1 |

## 5. 代码改动点

### 5.1 新增 / 修改文件

| 文件 | 改动 |
|---|---|
| `model/SwitchCase.java` | **新增**。`when` + `nodes`。 |
| `model/NodeDef.java` | 加 `cases`、`defaultCase` 字段。 |
| `node/SwitchNodeExecutor.java` | **新增**。实现分支评估 + 子节点调度；实现 `NodeExecutor` 接口。 |
| `node/NodeRunner.java` | **新增**。抽出"顺序执行一组 nodes 并收集错误"的统一入口，注入各 NodeExecutor，支持任意嵌套。顶层循环、SWITCH、ITERATE 子节点均调它。 |
| `service/PipelineExecutor.java` | 顶层循环改为调 `NodeRunner.run(config.getNodes(), context, nodeErrors)`；`executeNode` 的 switch 加 `case "SWITCH"` 分发；移除原内联遍历逻辑。 |
| `http/OutputScriptExecutor.java` | 把"准备 JS 绑定变量"抽成可复用方法（如 `prepareBindings(context)`），供 `when` 求值复用；新增 `evaluateCondition(String expr, context)` 返回 boolean。 |
| `node/IterateNodeExecutor.java` | 若现有内联遍历 subPipeline.nodes，改为调 `NodeRunner.run`（小重构，保证嵌套 SWITCH 在 ITERATE 内正确执行）。需在实现阶段先读其实现再定。 |
| `controller/MockApiController.java` | 新增 3 个 mock 端点（见第 7 节）。 |
| `config/DemoDataInitializer.java` | 注册 3 个 API + 写入 `USER_TYPE_SWITCH` 编排。 |
| `templates/pipeline/form.html` | `addNode('SWITCH')` 分支 + 配置预览渲染 + 提交回带 `cases`/`defaultCase`（沿用 FORK/ITERATE 的 `dataset.originalNode` 模式）。 |

### 5.2 核心重构：`NodeRunner`

现状执行逻辑散落三处：`PipelineExecutor` 顶层循环、`IterateNodeExecutor` 内联遍历，SWITCH 将引入第四处。
为避免重复并保证嵌套行为一致，抽出：

```java
@Component
public class NodeRunner {
    @Resource private SerialNodeExecutor serialNodeExecutor;
    @Resource private ForkNodeExecutor forkNodeExecutor;
    @Resource private IterateNodeExecutor iterateNodeExecutor;
    @Resource private SwitchNodeExecutor switchNodeExecutor;

    public void run(List<NodeDef> nodes, ExecutionContext context, List<Map<String,Object>> nodeErrors) {
        if (nodes == null) return;
        for (NodeDef node : nodes) {
            executeNode(node, context, nodeErrors);
        }
    }

    private void executeNode(NodeDef node, ExecutionContext context, List<Map<String,Object>> nodeErrors) {
        // 原 PipelineExecutor.executeNode 的 switch 分发 + 错误收集逻辑搬到此
        // case "SWITCH": switchNodeExecutor.execute(node, context, nodeErrors);
    }
}
```

`SwitchNodeExecutor` 注入 `NodeRunner` 回调执行命中 case 的子节点，天然递归支持任意嵌套。
`PipelineExecutor` 注入 `NodeRunner` 执行顶层 nodes。无循环依赖（`NodeRunner` 依赖各 executor，`SwitchNodeExecutor` 依赖 `NodeRunner`，Spring 用 `@Lazy` 或构造/setter 注入处理）。

## 6. 错误处理

| 场景 | 处理 |
|---|---|
| `when` JS 表达式抛异常 / 求值非 boolean | 该 case 视为不命中，继续评估下一个 case；记录 WARN 日志（nodeId、case 索引、表达式、异常）。不中断编排。 |
| 所有 case 因异常/求值失败未命中 | 走 `defaultCase`（若有），否则 SWITCH 通过、matchedCase=-1。 |
| 命中 case 的子节点失败 | 子节点按现有规则记 nodeErrors（httpStatus≥400），不中断 case 内后续子节点、也不中断 SWITCH 之后节点；最终 status 可能 PARTIAL。 |
| `cases` 为空或缺省 | 直接走 `defaultCase`；都无则 SWITCH 通过（matchedCase=-1）。 |
| 命中 case 的 `nodes` 为空 | matchedCase 记该索引，什么都不执行，SWITCH 通过。 |

## 7. 数据库示例案例

### 7.1 新增 mock 端点（`MockApiController`）

- `POST /mock-api/user/type`：入参 `id`，返回 `{code:"200", data:{userid:"user-"+id, type:"VIP"|"NORMAL"}}`。规则：id 末位偶数 → VIP，奇数 → NORMAL。（`1001`→NORMAL，`1002`→VIP）
- `POST /mock-api/vip/discount`：入参 `userid`，返回 VIP 专属折扣信息。
- `POST /mock-api/normal/info`：入参 `userid`，返回普通用户信息。

### 7.2 新增 API 注册（`DemoDataInitializer`）

- `GET_USER_TYPE` → POST `/mock-api/user/type`
- `GET_VIP_DISCOUNT` → POST `/mock-api/vip/discount`
- `GET_NORMAL_INFO` → POST `/mock-api/normal/info`

### 7.3 新增编排 `USER_TYPE_SWITCH`

- 入参：`id`（string, required）
- 节点：
  - `n1` SERIAL `GET_USER_TYPE`，input `{id: ${request.id}}`
  - `sw1` SWITCH，cases：
    - `[0]` when `n1.data.type === 'VIP'` → nodes: `n2` SERIAL `GET_VIP_DISCOUNT` input `{userid: ${n1.data.userid}}`
    - `[1]` when `n1.data.type === 'NORMAL'` → nodes: `n3` SERIAL `GET_NORMAL_INFO` input `{userid: ${n1.data.userid}}`
  - `defaultCase: []`
- outputScript：

```javascript
return {
  userId: n1.data.userid,
  userType: n1.data.type,
  branch: n2 ? 'VIP' : 'NORMAL',
  detail: n2 ? n2.data : (n3 ? n3.data : null),
  matchedCase: sw1.data.matchedCase
};
```

### 7.4 验证

- `curl -X POST .../USER_TYPE_SWITCH/execute -d '{"id":"1002"}'` → VIP 分支，detail 来自 n2，matchedCase=0
- `curl -X POST .../USER_TYPE_SWITCH/execute -d '{"id":"1001"}'` → NORMAL 分支，detail 来自 n3，matchedCase=1

## 8. 测试策略

项目当前无任何测试。本次新增功能补首个测试基座：

1. **纯单元测试（不依赖 Spring/MySQL/Redis）**
   - `SwitchNodeExecutorTest`：mock `OutputScriptExecutor`（求值 `when`）和 `NodeRunner`，验证：
     - 第一个命中即停
     - 多 case 只跑一个
     - 全不命中走 defaultCase
     - 全不命中无 default 则通过、matchedCase=-1
     - when 异常时跳过该 case
     - matchedCase 取值约定（0 / -1 / -2）
   - `NodeRunnerTest`：递归调度（含 SWITCH 嵌套 SWITCH）、错误收集。
2. **集成冒烟（手动）**：通过 `USER_TYPE_SWITCH` 示例编排，启动应用后 curl 两种用户类型，确认分别走了不同分支。

## 9. 非目标（YAGNI）

- 不做 `switchOn`+`equals` 简写模式（纯 JS `when` 足够）。
- 不做 SWITCH 的 case 可视化编辑器（与现有 FORK/ITERATE 子节点编辑一致，统一走底部 JSON 文本框）。
- 不改 `ExpressionResolver` 支持运算符（条件求值统一走 Nashorn）。

## 10. 备注

- 项目当前不是 git 仓库，spec 文档已写入但无法 commit。
