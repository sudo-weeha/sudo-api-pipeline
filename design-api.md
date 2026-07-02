# API 编排平台设计方案

> 方案 B：串行 + 并行混合编排引擎
>
> 技术栈：Spring Boot + MyBatis + MySQL + Redis + Thymeleaf

---

## 快速开始：30 秒看懂这是干嘛的

### 场景

你有两个已有的 API：

```
API-1: 入参 id        → 返回 {"code":"200", "data":{"userid":"abc"}}
API-2: 入参 userid    → 返回 {"code":"200", "data":{"a":1, "b":2}}
```

你想把它们串起来，变成一个**新 API**：传入 `id`，先调 API-1 拿到 `userid`，再拿 `userid` 调 API-2，最终返回汇总结果。

### 怎么做

**第一步：注册两个 API。**

```sql
-- API-1
INSERT INTO api_registry (api_name, api_code, request_method, request_url)
VALUES ('获取用户ID', 'GET_USER_ID', 'POST', 'http://internal-api/user/query');

-- API-2
INSERT INTO api_registry (api_name, api_code, request_method, request_url)
VALUES ('获取用户详情', 'GET_USER_DETAIL', 'POST', 'http://internal-api/user/detail');
```

**第二步：创建一个编排，描述串联逻辑。**

```sql
INSERT INTO pipeline_def (pipeline_name, pipeline_code, pipeline_config)
VALUES (
    '用户信息串联',
    'USER_CHAIN',
    '{
        "inputParams": [
            { "name": "id", "type": "string", "required": true }
        ],
        "nodes": [
            {
                "nodeId": "n1",
                "nodeName": "获取用户ID",
                "nodeType": "SERIAL",
                "apiCode": "GET_USER_ID",
                "inputMapping": { "id": "${request.id}" }
            },
            {
                "nodeId": "n2",
                "nodeName": "获取用户详情",
                "nodeType": "SERIAL",
                "apiCode": "GET_USER_DETAIL",
                "inputMapping": { "userid": "${n1.data.data.userid}" }
            }
        ],
        "outputMapping": {
            "code": 0,
            "data": {
                "userId": "${n1.data.data.userid}",
                "detail": "${n2.data.data}"
            },
            "msg": "success"
        }
    }'
);
```

**第三步：调用产出的新 API。**

```bash
curl -X POST http://localhost:8080/api/pipeline/USER_CHAIN/execute \
  -H "Content-Type: application/json" \
  -d '{"id": "123"}'
```

**最终响应：**

```json
{
  "code": 0,
  "data": {
    "userId": "abc",
    "detail": { "a": 1, "b": 2 }
  },
  "msg": "success"
}
```

### 引擎内部做了什么

```
收到请求: {"id": "123"}
  │
  ├─ 节点 n1 "获取用户ID"
  │   ├─ 解析 inputMapping: {"id": "${request.id}"} → {"id": "123"}
  │   ├─ 查 Redis 缓存: key="GET_USER_ID:{\"id\":\"123\"}" → 未命中
  │   ├─ POST http://internal-api/user/query  Body: {"id":"123"}
  │   ├─ 下游返回: {"code":"200","data":{"userid":"abc"}}
  │   └─ context.put("n1", 200, 响应体)
  │       ${n1.data.data.userid} = "abc"
  │
  ├─ 节点 n2 "获取用户详情"
  │   ├─ 解析 inputMapping: {"userid": "${n1.data.data.userid}"} → {"userid":"abc"}
  │   ├─ POST http://internal-api/user/detail  Body: {"userid":"abc"}
  │   ├─ 下游返回: {"code":"200","data":{"a":1,"b":2}}
  │   └─ context.put("n2", 200, 响应体)
  │       ${n2.data.data} = {"a":1,"b":2}
  │
  └─ 解析 outputMapping → 最终响应
```

**核心就一句话：** `inputMapping` 里用 `${n1.data.data.userid}` 把上个接口的返回值取出来，传给下个接口。

---

## 一、整体架构

```
┌─────────────────────────────────────────────────────────┐
│                管理页面 (Thymeleaf + Bootstrap)            │
│              API 注册 · 编排配置 · 测试运行                │
└──────────────────────┬──────────────────────────────────┘
                       │ REST API
┌──────────────────────▼──────────────────────────────────┐
│                  Spring Boot 后端                          │
│                                                          │
│  ┌──────────┐  ┌──────────────┐  ┌───────────────────┐  │
│  │ API 注册  │  │  编排配置     │  │  编排执行引擎       │  │
│  │ 模块     │  │  模块         │  │  PipelineExecutor │  │
│  └──────────┘  └──────────────┘  └─────────┬─────────┘  │
│                                            │            │
│                              ┌─────────────▼──────────┐  │
│                              │  节点执行器              │  │
│                              │  SERIAL · FORK · ITERATE│  │
│                              └─────────────┬──────────┘  │
│                                            │            │
│  ┌──────────┐  ┌──────────────┐  ┌─────────▼─────────┐  │
│  │ Redis    │  │    MySQL      │  │   HTTP 调用层      │  │
│  │ 缓存     │  │  配置存储     │  │   (RestTemplate)  │  │
│  └──────────┘  └──────────────┘  └───────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 核心分层（4 层）

| 层 | 职责 | 说明 |
|---|---|---|
| **管理页面** | Thymeleaf 模板渲染，API 注册、编排配置的交互界面 | 用户操作入口 |
| **配置模块** | 注册 API 元信息、编排流程定义的 CRUD | 存什么、怎么存 |
| **执行引擎** | 解析编排配置 → 调度节点执行 → 汇总响应 | 核心逻辑 |
| **调用层** | 统一发起 HTTP 调用 + Redis 缓存命中 | 复用现有能力 |

### 关键设计决策

- 编排配置存 MySQL，JSON 字段存 DAG 拓扑
- 执行引擎无状态，每次请求实时解析配置执行
- 编排产出的"新 API"本质是一个 URL：`POST /api/pipeline/{pipelineCode}/execute`
- 复用现有 API 的日志和 Redis 缓存（30 分钟），编排层不做重复缓存
- 前端使用 Thymeleaf + Bootstrap，不引入 Vue/React，保持轻量

---

## 二、数据模型（3 张表）

### 1. `api_registry` — API 注册表

```sql
CREATE TABLE api_registry (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    api_name        VARCHAR(128)  NOT NULL COMMENT 'API 名称，如：获取用户信息',
    api_code        VARCHAR(64)   NOT NULL UNIQUE COMMENT 'API 唯一标识，如：GET_USER_INFO',
    request_method  VARCHAR(10)   NOT NULL COMMENT 'GET / POST / PUT / DELETE',
    request_url     VARCHAR(512)  NOT NULL COMMENT '目标 URL，支持占位符',
    request_headers JSON          COMMENT '固定请求头，JSON 格式',
    timeout_ms      INT           DEFAULT 5000 COMMENT '超时毫秒数',
    retry_count     INT           DEFAULT 0 COMMENT '重试次数',
    cache_ttl_min   INT           DEFAULT 30 COMMENT 'Redis 缓存 TTL（分钟），默认 30',
    description     VARCHAR(512)  COMMENT '描述说明',
    status          TINYINT       DEFAULT 1 COMMENT '1-启用 0-禁用',
    created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| api_name | VARCHAR(128) | API 名称，如：获取用户信息 |
| api_code | VARCHAR(64) UNIQUE | API 唯一标识，如：GET_USER_INFO |
| request_method | VARCHAR(10) | GET / POST / PUT / DELETE |
| request_url | VARCHAR(512) | 目标 URL，支持占位符如 `/api/user/{userId}` |
| request_headers | JSON | 固定请求头，JSON 格式 |
| timeout_ms | INT | 超时毫秒数，默认 5000 |
| retry_count | INT | 重试次数，默认 0 |
| cache_ttl_min | INT | Redis 缓存 TTL（分钟），默认 30 |
| description | VARCHAR(512) | 描述说明 |
| status | TINYINT | 1-启用 0-禁用 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 2. `pipeline_def` — 编排定义表

```sql
CREATE TABLE pipeline_def (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    pipeline_name   VARCHAR(128)  NOT NULL COMMENT '编排名称',
    pipeline_code   VARCHAR(64)   NOT NULL UNIQUE COMMENT '编排唯一标识，也是产出的新 API 路径',
    pipeline_config JSON          NOT NULL COMMENT '编排拓扑 JSON（核心字段）',
    description     VARCHAR(512)  COMMENT '描述',
    status          TINYINT       DEFAULT 1 COMMENT '1-启用 0-禁用',
    created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| pipeline_name | VARCHAR(128) | 编排名称 |
| pipeline_code | VARCHAR(64) UNIQUE | 编排唯一标识，也是产出的新 API 路径 |
| pipeline_config | JSON | 编排拓扑 JSON（核心字段） |
| description | VARCHAR(512) | 描述 |
| status | TINYINT | 1-启用 0-禁用 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 3. `pipeline_exec_log` — 执行日志表

```sql
CREATE TABLE pipeline_exec_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    pipeline_id     BIGINT        NOT NULL COMMENT '编排 ID',
    pipeline_code   VARCHAR(64)   NOT NULL COMMENT '编排 code（冗余，便于查询）',
    request_params  JSON          COMMENT '本次请求的输入参数',
    node_results    JSON          COMMENT '各节点执行结果',
    final_response  JSON          COMMENT '最终响应',
    total_ms        INT           COMMENT '总耗时（毫秒）',
    status          VARCHAR(16)   NOT NULL COMMENT 'SUCCESS / PARTIAL / FAILED',
    error_msg       VARCHAR(1024) COMMENT '错误信息',
    created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP
);
```

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| pipeline_id | BIGINT | 编排 ID |
| pipeline_code | VARCHAR(64) | 编排 code（冗余，便于查询） |
| request_params | JSON | 本次请求的输入参数 |
| node_results | JSON | 各节点执行结果 |
| final_response | JSON | 最终响应 |
| total_ms | INT | 总耗时（毫秒） |
| status | VARCHAR(16) | SUCCESS / PARTIAL / FAILED |
| error_msg | VARCHAR(1024) | 错误信息 |
| created_at | DATETIME | 创建时间 |

---

## 三、pipeline_config JSON 结构（核心）

### 完整结构定义

```json
{
  "inputParams": [
    {
      "name": "参数名",
      "type": "string|number|boolean",
      "required": true,
      "description": "参数说明"
    }
  ],
  "nodes": [
    // 节点列表，按序执行
  ],
  "outputMapping": {
    // 定义最终响应格式
  }
}
```

### 节点类型

| 节点类型 | 说明 |
|---------|------|
| `SERIAL` | 调用单个 API，上一步完成才执行下一步 |
| `FORK` | 多个 API 并行调用，内部 tasks 并发执行 |
| `ITERATE` | 遍历数组或切割字符串，对每个元素执行子编排，支持并发控制 |

### 表达式语法

| 表达式 | 含义 |
|--------|------|
| `${request.userId}` | 引用请求入参 |
| `${n1.data}` | 节点 n1 的完整响应体 |
| `${n1.data.id}` | 响应体中的 `id` 字段 |
| `${n1.data.orders}` | 响应体中的 `orders` 字段（数组） |
| `${n1.data.orders[0]}` | 数组第一个元素 |
| `${n1.data.user.name}` | 嵌套字段 |
| `${n1.httpStatus}` | HTTP 状态码 |
| `${order.id}` | ITERATE 中当前迭代项的 `id` 字段 |

### 完整编排示例（SERIAL + FORK + ITERATE）

```json
{
  "inputParams": [
    { "name": "userId", "type": "string", "required": true, "description": "用户ID" }
  ],
  "nodes": [
    {
      "nodeId": "n1",
      "nodeName": "获取用户信息",
      "nodeType": "SERIAL",
      "apiCode": "GET_USER",
      "inputMapping": {
        "userId": "${request.userId}"
      }
    },
    {
      "nodeId": "fork1",
      "nodeName": "并行查询",
      "nodeType": "FORK",
      "tasks": [
        {
          "nodeId": "n2",
          "nodeName": "查订单列表",
          "apiCode": "GET_ORDER_LIST",
          "inputMapping": {
            "userId": "${n1.data.id}"
          }
        },
        {
          "nodeId": "n3",
          "nodeName": "查积分",
          "apiCode": "GET_POINTS",
          "inputMapping": {
            "userId": "${n1.data.id}"
          }
        }
      ]
    },
    {
      "nodeId": "loop1",
      "nodeName": "逐条查订单详情",
      "nodeType": "ITERATE",
      "iterateOn": "${n2.data}",
      "itemAlias": "order",
      "maxConcurrency": 5,
      "subPipeline": {
        "nodes": [
          {
            "nodeId": "n4",
            "nodeName": "查订单详情",
            "nodeType": "SERIAL",
            "apiCode": "GET_ORDER_DETAIL",
            "inputMapping": {
              "orderId": "${order.id}"
            }
          },
          {
            "nodeId": "n5",
            "nodeName": "查物流",
            "nodeType": "SERIAL",
            "apiCode": "GET_LOGISTICS",
            "inputMapping": {
              "orderId": "${order.id}"
            }
          }
        ]
      }
    },
    {
      "nodeId": "n6",
      "nodeName": "汇总结果",
      "nodeType": "SERIAL",
      "apiCode": "MERGE_REPORT",
      "inputMapping": {
        "user": "${n1.data}",
        "orders": "${n2.data}",
        "points": "${n3.data}",
        "details": "${loop1.data}"
      }
    }
  ],
  "outputMapping": {
    "code": 0,
    "data": "${n6.data}",
    "msg": "success"
  }
}
```

---

## 四、ITERATE 节点详解

### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `iterateOn` | String | ✅ | 表达式求值，可以是数组或字符串 |
| `splitBy` | String | ❌ | 当 `iterateOn` 解析为字符串时，按此分隔符切割；解析为数组时忽略 |
| `itemAlias` | String | ✅ | 当前元素变量名，子编排中用 `${alias}` 或 `${alias.字段}` 引用 |
| `maxConcurrency` | Integer | ✅ | 最大并发数，控流不压垮下游 |
| `subPipeline` | Object | ✅ | 子编排定义，包含 `nodes` 数组，每个节点目前仅支持 `SERIAL` |

**`itemAlias` 取值规则：**

| `iterateOn` 解析结果 | item 类型 | 取值方式 | 示例 |
|---------------------|----------|---------|------|
| `["abc","def"]` | 字符串 | `${uid}` | `${uid}` → `"abc"` |
| `[{"userid":"abc"},...]` | 对象 | `${user.字段}` | `${user.userid}` → `"abc"` |
| `"abc,def"` + `splitBy:","` | 字符串 | `${uid}` | `${uid}` → `"abc"` |

### 场景一：遍历数组

**上游 API 返回数组，对每个元素调用下游 API。**

上游 API `GET_DEPT_USERS` 返回：

```json
{"code":"200", "data": {"users": [
  {"userid":"abc", "name":"张三"},
  {"userid":"def", "name":"李四"},
  {"userid":"ghi", "name":"王五"}
]}}
```

编排配置：

```json
{
  "nodeId": "loop1",
  "nodeName": "逐条查用户详情",
  "nodeType": "ITERATE",
  "iterateOn": "${n1.data.data.users}",
  "itemAlias": "user",
  "maxConcurrency": 5,
  "subPipeline": {
    "nodes": [
      {
        "nodeId": "n2",
        "nodeName": "查用户详情",
        "nodeType": "SERIAL",
        "apiCode": "GET_USER_DETAIL",
        "inputMapping": {
          "userid": "${user.userid}"
        }
      }
    ]
  }
}
```

执行追踪：

```
n1 返回 → ${n1.data.data.users} = [{"userid":"abc",...}, {"userid":"def",...}, {"userid":"ghi",...}]

loop1 ITERATE:
  iterateOn 解析 → 得到数组，3 个元素
  Semaphore(5) 控制并发

  第1轮: item = {"userid":"abc","name":"张三"}
    → ${user.userid} = "abc"
    → POST /user/detail  Body: {"userid":"abc"}
    → 返回 {"code":"200","data":{"age":25,"addr":"北京"}}

  第2轮: item = {"userid":"def","name":"李四"}
    → ${user.userid} = "def"
    → POST /user/detail  Body: {"userid":"def"}
    → 返回 {"code":"200","data":{"age":30,"addr":"上海"}}

  第3轮: item = {"userid":"ghi","name":"王五"}
    → ${user.userid} = "ghi"
    → POST /user/detail  Body: {"userid":"ghi"}
    → 返回 {"code":"200","data":{"age":28,"addr":"广州"}}

  loop1 结果:
  {
    "data": [
      { "user": {"userid":"abc","name":"张三"}, "n2": {"httpStatus":200, "data":{"age":25,"addr":"北京"}} },
      { "user": {"userid":"def","name":"李四"}, "n2": {"httpStatus":200, "data":{"age":30,"addr":"上海"}} },
      { "user": {"userid":"ghi","name":"王五"}, "n2": {"httpStatus":200, "data":{"age":28,"addr":"广州"}} }
    ],
    "totalCount": 3,
    "successCount": 3,
    "failedCount": 0
  }
```

### 场景二：切割逗号分隔字符串

**上游 API 返回逗号分隔的字符串，切割后逐条调用下游 API。**

上游 API `GET_USER_IDS` 返回：

```json
{"code":"200", "data": {"userids": "abc,def,ghi"}}
```

编排配置：

```json
{
  "nodeId": "loop1",
  "nodeName": "逐条查用户详情",
  "nodeType": "ITERATE",
  "iterateOn": "${n1.data.data.userids}",
  "splitBy": ",",
  "itemAlias": "uid",
  "maxConcurrency": 5,
  "subPipeline": {
    "nodes": [
      {
        "nodeId": "n2",
        "nodeName": "查用户详情",
        "nodeType": "SERIAL",
        "apiCode": "GET_USER_DETAIL",
        "inputMapping": {
          "userid": "${uid}"
        }
      }
    ]
  }
}
```

执行追踪：

```
n1 返回 → ${n1.data.data.userids} = "abc,def,ghi"

loop1 ITERATE:
  iterateOn 解析 → "abc,def,ghi"
  splitBy = "," → 引擎内部自动切割为 ["abc", "def", "ghi"]
  Semaphore(5) 控制并发

  第1轮: item = "abc"  → ${uid} = "abc"  → POST /user/detail Body:{"userid":"abc"}
  第2轮: item = "def"  → ${uid} = "def"  → POST /user/detail Body:{"userid":"def"}
  第3轮: item = "ghi"  → ${uid} = "ghi"  → POST /user/detail Body:{"userid":"ghi"}

  loop1 结果:
  {
    "data": [
      { "item": "abc", "n2": {"httpStatus":200, "data":{"age":25}} },
      { "item": "def", "n2": {"httpStatus":200, "data":{"age":30}} },
      { "item": "ghi", "n2": {"httpStatus":200, "data":{"age":28}} }
    ],
    "totalCount": 3,
    "successCount": 3,
    "failedCount": 0
  }
```

### ITERATE 执行结果结构

```json
{
  "nodeId": "loop1",
  "httpStatus": 200,
  "data": [
    {
      "order": { "id": 1, "name": "订单A" },
      "n4": { "httpStatus": 200, "data": { "detail": "..." } },
      "n5": { "httpStatus": 200, "data": { "logistics": "..." } }
    },
    {
      "order": { "id": 2, "name": "订单B" },
      "n4": { "httpStatus": 200, "data": { "detail": "..." } },
      "n5": { "httpStatus": 500, "data": null }
    }
  ],
  "totalCount": 2,
  "successCount": 1,
  "failedCount": 1
}
```

---

## 五、核心类设计

### 类结构总览

```
src/main/java/com/example/pipeline/
├── controller/
│   ├── ApiRegistryController          -- API 注册 CRUD + 页面渲染
│   ├── PipelineController             -- 编排 CRUD + 执行入口 + 页面渲染
│   └── PageController                 -- Thymeleaf 页面路由
├── service/
│   ├── ApiRegistryService             -- API 注册业务逻辑
│   ├── PipelineDefService             -- 编排配置业务逻辑
│   ├── PipelineExecutor               -- 编排执行引擎（核心）
│   └── PipelineExecLogService         -- 执行日志
├── node/
│   ├── NodeExecutor                   -- 接口：节点执行器
│   ├── SerialNodeExecutor             -- SERIAL 节点实现
│   ├── ForkNodeExecutor               -- FORK 节点实现
│   └── IterateNodeExecutor            -- ITERATE 节点实现
├── http/
│   ├── ApiInvoker                     -- HTTP 调用 + Redis 缓存命中
│   └── ExpressionResolver             -- ${...} 表达式解析引擎
├── model/
│   ├── PipelineConfig                 -- pipeline_config JSON 的 Java 映射
│   ├── NodeDef                        -- 单个节点定义
│   ├── ForkDef                        -- FORK 分支定义
│   ├── IterateDef                     -- ITERATE 定义
│   ├── SubPipelineDef                 -- 子编排定义
│   ├── ExecutionContext               -- 运行时上下文（承载中间结果）
│   └── NodeResult                     -- 节点执行结果
├── repository/
│   ├── ApiRegistryMapper              -- MyBatis Mapper
│   ├── PipelineDefMapper              -- MyBatis Mapper
│   └── PipelineExecLogMapper          -- MyBatis Mapper
└── dto/
    ├── ApiRegistryDTO
    ├── PipelineDefDTO
    └── PipelineExecRequest            -- 编排执行请求 DTO

src/main/resources/templates/
├── layout.html                        -- Thymeleaf 公共布局（导航栏 + 样式）
├── api-registry/
│   ├── list.html                      -- API 列表页
│   └── form.html                      -- API 新增/编辑页
├── pipeline/
│   ├── list.html                      -- 编排列表页
│   ├── form.html                      -- 编排新增/编辑页
│   └── test.html                      -- 编排测试运行页
└── exec-log/
    └── list.html                      -- 执行日志列表页
```

### 关键接口

```java
// 节点执行器接口
public interface NodeExecutor {
    void execute(NodeDef node, ExecutionContext context);
}

// API 调用器（含缓存）
public interface ApiInvoker {
    Object invoke(String apiCode, Map<String, Object> resolvedParams);
}

// 表达式解析器
public interface ExpressionResolver {
    Object resolve(Map<String, Object> template, ExecutionContext context);
    Object resolveExpression(String expression, ExecutionContext context);
    List<Object> resolveArray(String expression, ExecutionContext context);
}
```

### ExecutionContext — 运行时数据载体

```java
public class ExecutionContext {
    // ${request.xxx} 的值
    private Map<String, Object> requestParams;

    // 每个节点执行结果的缓存，key = nodeId
    private Map<String, NodeResult> nodeResults = new ConcurrentHashMap<>();

    // ITERATE 中当前迭代项，key = itemAlias
    private Map<String, Object> currentItem = new HashMap<>();

    public void put(String nodeId, int httpStatus, Object responseBody, long elapsedMs) {
        nodeResults.put(nodeId, new NodeResult(httpStatus, responseBody, elapsedMs));
    }

    public NodeResult get(String nodeId) { ... }

    public ExecutionContext fork() {
        // 创建子上下文，继承 requestParams，独立 nodeResults
    }

    public void putItem(String alias, Object item) {
        currentItem.put(alias, item);
    }
}
```

---

## 六、核心调用链路

```
POST /api/pipeline/{pipelineCode}/execute
  │
  ▼
PipelineController.execute(pipelineCode, requestParams)
  │
  ▼
PipelineExecutor.execute(pipelineCode, requestParams)
  │
  ├─ 1. PipelineDefService.getByCode(pipelineCode)  → PipelineConfig
  ├─ 2. 解析 pipelineConfig.nodes，构建执行计划
  ├─ 3. 创建 ExecutionContext（初始化 ${request.xxx}）
  │
  ├─ 4. 遍历 nodes 列表：
  │     │
  │     ├─ nodeType = SERIAL → SerialNodeExecutor.execute(node, context)
  │     │    ├─ ExpressionResolver.resolve(inputMapping, context)
  │     │    ├─ ApiInvoker.invoke(apiCode, resolvedParams)
  │     │    │   ├─ 查 Redis 缓存（key = apiCode + paramsHash）
  │     │    │   ├─ 缓存命中 → 直接返回
  │     │    │   └─ 缓存未命中 → RestTemplate 调用 → 写缓存(30min)
  │     │    └─ context.put(nodeId, httpStatus, responseBody, elapsedMs)
  │     │
  │     ├─ nodeType = FORK → ForkNodeExecutor.execute(node, context)
  │     │    └─ tasks 列表用 CompletableFuture.allOf() 并行
  │     │       └─ 每个 task 走 SerialNodeExecutor 逻辑
  │     │    └─ context.put(taskNodeId, httpStatus, responseBody, elapsedMs)
  │     │
  │     └─ nodeType = ITERATE → IterateNodeExecutor.execute(node, context)
  │          ├─ 解析 iterateOn 表达式，得到值
  │          ├─ 如果有 splitBy → 按分隔符切割字符串为数组
  │          ├─ 如果没有 splitBy 且值为数组 → 直接遍历
  │          ├─ Semaphore(maxConcurrency) 控制并发数
  │          ├─ items.stream() → CompletableFuture 并行执行
  │          │   └─ 每个 item:
  │          │       ├─ context.fork() 创建子上下文
  │          │       ├─ context.putItem(itemAlias, item) 注入 ${alias}
  │          │       ├─ 遍历 subPipeline.nodes，调用 SerialNodeExecutor
  │          │       └─ 返回子上下文所有结果
  │          └─ 汇总所有子任务结果
  │             └─ context.put(nodeId, httpStatus, results[], elapsedMs)
  │             └─ 附加 totalCount / successCount / failedCount
  │
  ├─ 5. ExpressionResolver.resolve(outputMapping, context) → 最终响应
  └─ 6. PipelineExecLogService.save(log)
```

### IterateNodeExecutor 核心实现

```java
public class IterateNodeExecutor implements NodeExecutor {

    private final SerialNodeExecutor serialExecutor;
    private final ExpressionResolver resolver;
    private final ExecutorService threadPool;

    @Override
    public void execute(NodeDef node, ExecutionContext context) {
        IterateDef iterDef = node.getIterateDef();
        long start = System.currentTimeMillis();

        // 1. 解析 iterateOn 表达式
        Object raw = resolver.resolveExpression(iterDef.getIterateOn(), context);

        // 2. 如果有 splitBy，切割字符串；否则直接当数组
        List<Object> items;
        if (iterDef.getSplitBy() != null && raw instanceof String) {
            items = Arrays.stream(((String) raw).split(iterDef.getSplitBy()))
                .map(String::trim)
                .collect(Collectors.toList());
        } else if (raw instanceof List) {
            items = (List<Object>) raw;
        } else {
            throw new ExpressionException("ITERATE 只能遍历数组或带 splitBy 的字符串");
        }

        // 3. 信号量控制并发
        Semaphore semaphore = new Semaphore(iterDef.getMaxConcurrency());

        // 4. 每个元素创建一个子任务
        List<CompletableFuture<Map<String, Object>>> futures = items.stream()
            .map(item -> CompletableFuture.supplyAsync(() -> {
                semaphore.acquire();
                try {
                    ExecutionContext subContext = context.fork();
                    subContext.putItem(iterDef.getItemAlias(), item);

                    for (NodeDef subNode : iterDef.getSubPipeline().getNodes()) {
                        serialExecutor.execute(subNode, subContext);
                    }
                    return subContext.getAllResults();
                } finally {
                    semaphore.release();
                }
            }, threadPool))
            .collect(Collectors.toList());

        // 5. 等待所有子任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 6. 收集结果，区分成功/失败
        List<Map<String, Object>> results = new ArrayList<>();
        long successCount = 0, failedCount = 0;
        for (CompletableFuture<Map<String, Object>> future : futures) {
            try {
                results.add(future.get());
                successCount++;
            } catch (ExecutionException e) {
                results.add(Map.of("error", e.getCause().getMessage()));
                failedCount++;
            }
        }

        // 7. 写入 context
        long elapsedMs = System.currentTimeMillis() - start;
        Map<String, Object> result = Map.of(
            "data", results,
            "totalCount", items.size(),
            "successCount", successCount,
            "failedCount", failedCount
        );
        context.put(node.getNodeId(), failedCount == 0 ? 200 : 206, result, elapsedMs);
    }
}
```

---

## 七、REST API 清单（共 9 个）

### API 注册

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api-registry` | 注册一个新 API |
| `DELETE` | `/api-registry/{id}` | 删除 |
| `PUT` | `/api-registry/{id}` | 修改（URL、超时、缓存等） |
| `GET` | `/api-registry` | 列表查询（编排页面下拉选择用） |

### Pipeline 编排

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/pipeline-def` | 创建编排定义 |
| `PUT` | `/pipeline-def/{id}` | 修改编排拓扑 |
| `DELETE` | `/pipeline-def/{id}` | 删除 |
| `GET` | `/pipeline-def/{id}` | 查看编排详情（含完整 JSON） |

### 执行

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/pipeline/{pipelineCode}/execute` | 执行编排，即产出的新 API |

### 完整调用示例

```bash
# 1. 注册 API-1
curl -X POST http://localhost:8080/api-registry \
  -H "Content-Type: application/json" \
  -d '{
    "apiName": "获取用户ID",
    "apiCode": "GET_USER_ID",
    "requestMethod": "POST",
    "requestUrl": "http://internal-api/user/query",
    "timeoutMs": 5000,
    "cacheTtlMin": 30
  }'

# 2. 注册 API-2
curl -X POST http://localhost:8080/api-registry \
  -H "Content-Type: application/json" \
  -d '{
    "apiName": "获取用户详情",
    "apiCode": "GET_USER_DETAIL",
    "requestMethod": "POST",
    "requestUrl": "http://internal-api/user/detail",
    "timeoutMs": 5000,
    "cacheTtlMin": 30
  }'

# 3. 创建编排
curl -X POST http://localhost:8080/pipeline-def \
  -H "Content-Type: application/json" \
  -d '{
    "pipelineName": "用户信息串联",
    "pipelineCode": "USER_CHAIN",
    "pipelineConfig": {
      "inputParams": [
        { "name": "id", "type": "string", "required": true }
      ],
      "nodes": [
        {
          "nodeId": "n1",
          "nodeName": "获取用户ID",
          "nodeType": "SERIAL",
          "apiCode": "GET_USER_ID",
          "inputMapping": { "id": "${request.id}" }
        },
        {
          "nodeId": "n2",
          "nodeName": "获取用户详情",
          "nodeType": "SERIAL",
          "apiCode": "GET_USER_DETAIL",
          "inputMapping": { "userid": "${n1.data.data.userid}" }
        }
      ],
      "outputMapping": {
        "code": 0,
        "data": {
          "userId": "${n1.data.data.userid}",
          "detail": "${n2.data.data}"
        },
        "msg": "success"
      }
    }
  }'

# 4. 执行编排
curl -X POST http://localhost:8080/api/pipeline/USER_CHAIN/execute \
  -H "Content-Type: application/json" \
  -d '{"id": "123"}'

# 5. 响应
{
  "code": 0,
  "data": {
    "userId": "abc",
    "detail": { "a": 1, "b": 2 }
  },
  "msg": "success"
}
```

---

## 八、Thymeleaf 页面设计

### 页面路由

| 路径 | 页面 | 说明 |
|------|------|------|
| `GET /page/api-registry` | api-registry/list.html | API 列表 |
| `GET /page/api-registry/add` | api-registry/form.html | 新增 API |
| `GET /page/api-registry/{id}/edit` | api-registry/form.html | 编辑 API |
| `GET /page/pipeline` | pipeline/list.html | 编排列表 |
| `GET /page/pipeline/add` | pipeline/form.html | 新增编排 |
| `GET /page/pipeline/{id}/edit` | pipeline/form.html | 编辑编排 |
| `GET /page/pipeline/{id}/test` | pipeline/test.html | 测试运行 |
| `GET /page/exec-log` | exec-log/list.html | 执行日志 |

### 公共布局 `layout.html`

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title th:text="${title}">API 编排平台</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css">
</head>
<body>
<nav class="navbar navbar-expand-lg navbar-dark bg-dark mb-4">
    <div class="container">
        <a class="navbar-brand" href="/page/api-registry">API 编排平台</a>
        <div class="navbar-nav">
            <a class="nav-link" href="/page/api-registry">API 注册</a>
            <a class="nav-link" href="/page/pipeline">编排管理</a>
            <a class="nav-link" href="/page/exec-log">执行日志</a>
        </div>
    </div>
</nav>
<div class="container">
    <div th:replace="${content}"></div>
</div>
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
```

### API 注册列表页 `api-registry/list.html`

```html
<div th:fragment="content">
    <div class="d-flex justify-content-between align-items-center mb-3">
        <h3>API 注册列表</h3>
        <a href="/page/api-registry/add" class="btn btn-primary">+ 注册新 API</a>
    </div>

    <table class="table table-striped">
        <thead>
            <tr>
                <th>API 名称</th>
                <th>标识</th>
                <th>请求方式</th>
                <th>URL</th>
                <th>超时(ms)</th>
                <th>缓存(分)</th>
                <th>状态</th>
                <th>操作</th>
            </tr>
        </thead>
        <tbody>
            <tr th:each="api : ${apis}">
                <td th:text="${api.apiName}"></td>
                <td><code th:text="${api.apiCode}"></code></td>
                <td><span class="badge bg-secondary" th:text="${api.requestMethod}"></span></td>
                <td th:text="${api.requestUrl}"></td>
                <td th:text="${api.timeoutMs}"></td>
                <td th:text="${api.cacheTtlMin}"></td>
                <td>
                    <span class="badge" th:classappend="${api.status == 1} ? 'bg-success' : 'bg-danger'"
                          th:text="${api.status == 1} ? '启用' : '禁用'"></span>
                </td>
                <td>
                    <a th:href="@{/page/api-registry/{id}/edit(id=${api.id})}" class="btn btn-sm btn-outline-secondary">编辑</a>
                    <button class="btn btn-sm btn-outline-danger"
                            th:attr="onclick=|deleteApi(${api.id})|">删除</button>
                </td>
            </tr>
        </tbody>
    </table>
</div>
```

### 编排配置页 `pipeline/form.html`

```html
<div th:fragment="content">
    <h3 th:text="${pipeline == null} ? '新增编排' : '编辑编排'"></h3>

    <form th:action="@{/pipeline-def}" method="post" id="pipelineForm">
        <input type="hidden" name="id" th:value="${pipeline?.id}">

        <div class="row mb-3">
            <div class="col-md-6">
                <label class="form-label">编排名称</label>
                <input type="text" class="form-control" name="pipelineName"
                       th:value="${pipeline?.pipelineName}" required>
            </div>
            <div class="col-md-6">
                <label class="form-label">编排标识（产出 API 路径）</label>
                <input type="text" class="form-control" name="pipelineCode"
                       th:value="${pipeline?.pipelineCode}" required>
                <small class="text-muted">调用路径：POST /api/pipeline/{标识}/execute</small>
            </div>
        </div>

        <!-- 输入参数定义 -->
        <div class="card mb-3">
            <div class="card-header d-flex justify-content-between align-items-center">
                <strong>输入参数</strong>
                <button type="button" class="btn btn-sm btn-outline-primary" onclick="addInputParam()">+ 添加</button>
            </div>
            <div class="card-body" id="inputParamsContainer">
                <!-- 动态行 -->
                <div class="row mb-2 input-param-row">
                    <div class="col-md-3">
                        <input type="text" class="form-control" placeholder="参数名" name="inputParamName">
                    </div>
                    <div class="col-md-2">
                        <select class="form-select" name="inputParamType">
                            <option value="string">string</option>
                            <option value="number">number</option>
                            <option value="boolean">boolean</option>
                        </select>
                    </div>
                    <div class="col-md-2">
                        <div class="form-check">
                            <input class="form-check-input" type="checkbox" name="inputParamRequired" checked>
                            <label class="form-check-label">必填</label>
                        </div>
                    </div>
                    <div class="col-md-4">
                        <input type="text" class="form-control" placeholder="描述" name="inputParamDesc">
                    </div>
                    <div class="col-md-1">
                        <button type="button" class="btn btn-sm btn-outline-danger" onclick="this.closest('.input-param-row').remove()">×</button>
                    </div>
                </div>
            </div>
        </div>

        <!-- 节点编排 -->
        <div class="card mb-3">
            <div class="card-header d-flex justify-content-between align-items-center">
                <strong>节点编排（按序执行）</strong>
                <button type="button" class="btn btn-sm btn-outline-primary" onclick="addNode('SERIAL')">+ 串行节点</button>
                <button type="button" class="btn btn-sm btn-outline-info" onclick="addNode('FORK')">+ 并行节点</button>
                <button type="button" class="btn btn-sm btn-outline-warning" onclick="addNode('ITERATE')">+ 循环节点</button>
            </div>
            <div class="card-body" id="nodesContainer">
                <!-- 动态生成节点配置区域 -->
            </div>
        </div>

        <!-- 输出映射 -->
        <div class="card mb-3">
            <div class="card-header"><strong>输出映射</strong></div>
            <div class="card-body">
                <textarea class="form-control font-monospace" name="outputMapping" rows="6"
                          placeholder='{"code":0,"data":"${n2.data}","msg":"success"}'
                          th:text="${pipeline?.pipelineConfig?.outputMapping}"></textarea>
                <small class="text-muted">JSON 格式，支持 ${...} 表达式</small>
            </div>
        </div>

        <button type="submit" class="btn btn-primary">保存</button>
        <a th:href="@{/page/pipeline/{id}/test(id=${pipeline?.id})}"
           th:if="${pipeline != null}" class="btn btn-outline-success">测试运行</a>
    </form>
</div>
```

### 测试运行页 `pipeline/test.html`

```html
<div th:fragment="content">
    <h3>测试运行：<span th:text="${pipeline.pipelineName}"></span></h3>
    <p class="text-muted">调用路径：POST /api/pipeline/<code th:text="${pipeline.pipelineCode}"></code>/execute</p>

    <div class="row">
        <!-- 左侧：输入参数 -->
        <div class="col-md-6">
            <div class="card mb-3">
                <div class="card-header"><strong>输入参数</strong></div>
                <div class="card-body">
                    <div class="mb-3" th:each="param : ${pipeline.pipelineConfig.inputParams}">
                        <label class="form-label" th:text="${param.name} + (${param.required} ? ' *' : '')"></label>
                        <input type="text" class="form-control test-input"
                               th:attr="data-name=${param.name}"
                               th:required="${param.required}">
                        <small class="text-muted" th:text="${param.description}"></small>
                    </div>
                    <button class="btn btn-success" onclick="executePipeline()">▶ 执行</button>
                </div>
            </div>
        </div>

        <!-- 右侧：执行结果 -->
        <div class="col-md-6">
            <div class="card">
                <div class="card-header d-flex justify-content-between">
                    <strong>响应结果</strong>
                    <span id="execTime" class="text-muted"></span>
                </div>
                <div class="card-body">
                    <pre id="execResult" class="bg-light p-3 rounded"
                         style="max-height: 500px; overflow-y: auto;">点击"执行"查看结果...</pre>
                </div>
            </div>
        </div>
    </div>
</div>

<script th:inline="javascript">
    function executePipeline() {
        const params = {};
        document.querySelectorAll('.test-input').forEach(input => {
            params[input.dataset.name] = input.value;
        });

        const code = /*[[${pipeline.pipelineCode}]]*/ '';

        fetch('/api/pipeline/' + code + '/execute', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(params)
        })
        .then(res => res.json())
        .then(data => {
            document.getElementById('execResult').textContent = JSON.stringify(data, null, 2);
        })
        .catch(err => {
            document.getElementById('execResult').textContent = 'Error: ' + err.message;
        });
    }
</script>
```

---

## 九、错误处理策略

### 节点级错误

| 场景 | 处理策略 |
|------|---------|
| API 调用超时 | 捕获 `TimeoutException`，节点结果标记 `httpStatus: 408`，继续执行后续节点 |
| API 返回 4xx/5xx | 不抛异常，记录 `httpStatus` 和响应体到 context，后续节点可判断 |
| 表达式解析失败 | 抛出 `ExpressionException`，终止整个编排 |
| 网络不可达 | 捕获 `IOException`，重试 `retry_count` 次后标记失败 |

### Pipeline 级错误

| 场景 | 处理策略 |
|------|---------|
| 串行节点失败 | 继续执行后续节点（每个节点独立），最终 `status = PARTIAL` |
| FORK 中某任务失败 | 不影响其他并行任务，失败的 future 返回 null |
| ITERATE 中某迭代失败 | 捕获异常，记录到 results 中，`successCount` / `failedCount` 分别计数 |
| pipeline_config 解析失败 | 保存时校验，非法 JSON 拒绝入库 |

### 错误响应格式

```json
{
  "code": -1,
  "data": null,
  "msg": "编排执行部分失败",
  "errors": [
    { "nodeId": "n3", "nodeName": "查积分", "httpStatus": 500, "message": "Internal Server Error" }
  ]
}
```

---

## 十、关键约束与设计原则

1. **编排配置保存时校验**：JSON 结构合法性 + 引用的 `apiCode` 必须存在于 `api_registry`
2. **执行引擎无状态**：每次请求独立解析配置、创建新的 `ExecutionContext`，不依赖全局状态
3. **缓存复用**：编排层不重复缓存，`ApiInvoker` 层统一处理 Redis 缓存命中
4. **表达式安全**：`ExpressionResolver` 只支持字段取值和 split 切割，不支持脚本执行，防止注入
5. **并发控制**：`ITERATE` 必须通过 `maxConcurrency` 限流，防止打爆下游
6. **YAGNI**：当前不做条件分支、不做可视化拖拽、不做调度定时任务，只做配置式串行 + 并行 + 循环
