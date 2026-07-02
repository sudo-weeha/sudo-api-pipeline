# DAG 编排配置示例

当 `pipelineConfig.edges` 非空时，执行引擎会按 DAG 调度节点；没有 `edges` 时仍按旧的 `nodes` 顺序执行。

```json
{
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
    },
    {
      "nodeId": "n3",
      "nodeName": "获取订单",
      "nodeType": "SERIAL",
      "apiCode": "GET_ORDERS",
      "inputMapping": { "userid": "${n1.data.data.userid}" }
    }
  ],
  "edges": [
    { "from": "START", "to": "n1" },
    { "from": "n1", "to": "n2" },
    { "from": "n1", "to": "n3" },
    { "from": "n2", "to": "END" },
    { "from": "n3", "to": "END" }
  ],
  "dagConfig": {
    "maxConcurrency": 8,
    "failurePolicy": "CONTINUE"
  },
  "outputMapping": {
    "user": "${n2.data.data}",
    "orders": "${n3.data.data}"
  }
}
```

边字段：

- `from` / `to`: 节点连线，`START` 和 `END` 是虚拟节点。
- `onStatus`: `SUCCESS` / `FAILED` / `ANY`，默认 `SUCCESS`。
- `condition`: 可选 JavaScript 条件表达式，返回真值时才激活后继节点。
