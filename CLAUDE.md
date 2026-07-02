# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

API 编排平台 — a config-driven API orchestration engine. Users register downstream APIs, then compose them into pipelines via JSON configuration. Each pipeline becomes a new REST endpoint at `POST /api/pipeline/{pipelineCode}/execute`. This `dag-api` directory extends the original serial/fork/iterate engine with a **DAG scheduler** (edges-based) and a **SWITCH** conditional-branch node.

## Tech Stack

- **Java 11**, **Spring Boot 2.7.18**, **MyBatis 2.3.1**, **MySQL 8.0**, **Redis 7**
- **Thymeleaf** for management UI (Bootstrap 5 CDN, no SPA)
- **Nashorn** (OpenJDK Nashorn 15.4) for JS output scripting and condition evaluation (sandboxed — `ClassFilter` denies all Java access, `--language=es6`)
- **Lombok**, **Jackson**, **Maven**; tests use **JUnit 5 + Mockito + AssertJ**

## Build & Run

```bash
mvn -DskipTests package          # build jar
mvn spring-boot:run              # run app (needs MySQL + Redis on localhost)
mvn test                         # run all tests
mvn test -Dtest=DagPipelineExecutorTest              # single test class
mvn test -Dtest=SwitchNodeExecutorTest#命中第一个case_只执行该case的子节点_且context写入matchedCase0   # single test method

docker compose -f docker-compose.local.yml up -d    # local MySQL + Redis
docker build -t pipeline-api .                       # image
```

Tests are **pure JUnit unit tests** (no Spring context loaded) — collaborators are mocked and injected via reflection (`setField`). They do not require MySQL/Redis. MySQL connector auto-creates the DB (`createDatabaseIfNotExist=true`); `schema.sql` runs only when `spring.sql.init.mode` is not `never` (it is `never` in `application.yml`, so the schema is created manually or by the connector path).

## Architecture

```
controller/   → REST endpoints + Thymeleaf page routes + MockApiController + GlobalExceptionHandler
service/      → PipelineExecutor (entry), DagPipelineExecutor (DAG scheduler), DagValidator,
                NodeRunner-free services: PipelineDefService, ApiRegistryService, PipelineExecLogService
node/         → NodeRunner (dispatch hub), Serial/Fork/Iterate/Switch executors
http/         → ApiInvoker (+Redis cache, retry, pass-through error handler), ExpressionResolver (${...}),
                OutputScriptExecutor (Nashorn JS: outputScript + condition eval)
model/        → POJOs: PipelineConfig, NodeDef, EdgeDef, DagConfig, SwitchCase, ExecutionContext, ...
repository/   → MyBatis mappers (interfaces + XML under resources/mapper/)
dto/, config/ → DTOs; AppConfig (RestTemplate, RedisTemplate, ObjectMapper, pipelineTaskExecutor) + DemoDataInitializer
```

### Two execution modes (chosen per pipeline)

`PipelineExecutor.execute()` decides dispatch from the config:

- **Sequential mode** (legacy) — when `config.edges` is empty/null → `NodeRunner.run(config.getNodes(), ...)` walks `nodes[]` in order.
- **DAG mode** — when `config.edges` is non-empty → `DagPipelineExecutor.run(config, ...)` schedules nodes by topology.

### DAG scheduler (`DagPipelineExecutor`)

- `START` and `END` are virtual nodes (constants in `DagValidator`). Every DAG must have ≥1 `START→node` edge and ≥1 `node→END` edge.
- Edges carry optional `onStatus` (`SUCCESS`/`FAILED`/`ANY`, default `SUCCESS`) and `condition` (JS expression evaluated via `OutputScriptExecutor.evaluateCondition`). A child activates only when an incoming edge both status-matches and condition-matches.
- A node becomes ready when all its incoming edges have been processed AND at least one matched (`activatedIncoming > 0`). If none matched, the node is **skipped** (written to context with httpStatus 204, `{skipped:true}`) and skip propagates to its descendants.
- Runs on the `pipelineTaskExecutor` bean with `dagConfig.maxConcurrency` (default 8). Uses `ExecutorCompletionService` to take completed nodes and advance successors.
- `dagConfig.failurePolicy`: `FAIL_FAST` stops scheduling on first non-2xx (in-flight nodes finish); default `CONTINUE` runs everything. Node failures never throw — they're recorded, status becomes `PARTIAL`.
- Topology is validated up front by `DagValidator` (acyclicity via Kahn's algorithm, reserved-word/edge/existence checks, API-code references must exist in `api_registry`).

### Node dispatch (`NodeRunner`)

Single dispatch hub for `SERIAL`/`FORK`/`ITERATE`/`SWITCH`. The top-level loop, SWITCH children, and ITERATE sub-pipeline all run through `NodeRunner` to keep behavior consistent. `executeSingle` records any `httpStatus≥400` into the shared `nodeErrors` list.

- **SWITCH** (`SwitchNodeExecutor`) — evaluates each `cases[].when` (JS) in order, runs the first truthy case's child nodes via `NodeRunner`, else `defaultCase`. Writes `matchedCase` to context (`0..n-1`, or `-1` none/`-2` default). `when` evaluation failures are logged and treated as non-match (do not abort the pipeline). Injected `@Lazy` on `NodeRunner` to break the circular dependency.

### Expression & scripting

- `${...}` expressions (`ExpressionResolver`): `${request.x}`, `${n1.data}`, `${n1.data.user.name}`, `${n1.data.orders[0]}`, ITERATE item alias `${order.id}`.
- `outputScript` (Nashorn JS) for final response assembly — runs as an IIFE, may `return` a value; bindings expose `request`, `nodes` (each node's `{httpStatus,data,elapsedMs}` spread by nodeId), and current iteration item aliases.
- Same Nashorn engine evaluates edge `condition` and SWITCH `when` (`evaluateCondition`, JS truthiness semantics).

### Execution isolation

`ExecutionContext.fork()` (used by FORK/ITERATE) copies `requestParams` + current `nodeResults` into a child context. `nodeResults` is a `ConcurrentHashMap`. ITERATE throttles with `Semaphore(maxConcurrency)`.

### Key HTTP behavior

`RestTemplate` error handler is overridden to **never throw on 4xx/5xx** — status is passed through so downstream nodes / edge `onStatus` can react. `ApiInvokerImpl` does Redis caching (key = `apiCode + SHA-256(params)`), exponential-backoff retry, per-API timeout overrides.

## Pipeline config shape (JSON in `pipeline_def.pipeline_config`)

```json
{
  "inputParams": [{ "name": "id", "required": true }],
  "nodes": [ { "nodeId": "n1", "nodeType": "SERIAL", "apiCode": "GET_USER_ID", "inputMapping": {...} } ],
  "edges": [ { "from": "START", "to": "n1" }, { "from": "n1", "to": "n2", "onStatus": "SUCCESS", "condition": "n1.data.code === 0" } ],
  "dagConfig": { "maxConcurrency": 8, "failurePolicy": "CONTINUE" },
  "outputMapping": { "user": "${n2.data.data}" },
  "outputScript": "return { user: n2.data };"
}
```

`edges` non-empty ⇒ DAG mode. See `docs/dag-pipeline-example.md` for a worked example. Final response: `outputScript` takes precedence over `outputMapping`.

## Database

Three tables: `api_registry`, `pipeline_def` (`pipeline_config` JSON is the core field), `pipeline_exec_log`. Schema at `src/main/resources/schema.sql`. MyBatis maps via `resources/mapper/*.xml`; `map-underscore-to-camel-case` is on.

## REST API Summary

| Method | Path | Purpose |
|--------|------|---------|
| POST/GET/PUT/DELETE | `/api-registry[/{id}]` | API registration CRUD |
| POST/GET/PUT/DELETE | `/pipeline-def[/{id}]` | Pipeline definition CRUD (PUT updates topology) |
| POST | `/api/pipeline/{pipelineCode}/execute` | Execute pipeline → response `{status, data, errors}` (status: SUCCESS/PARTIAL) |
| GET | `/page/*` | Thymeleaf management UI |
| POST | `/mock-api/**` | Built-in mock API for local testing |

`execute` request body: either the params object directly, or `{"params": {...}}` (auto-unwrapped by `normalizeRequestParams`).

## Configuration

Env vars (`.env.example`): `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_REDIS_HOST`, `SPRING_REDIS_PORT`, `SERVER_PORT`. `application.yml` has localhost defaults. `DemoDataInitializer` seeds sample APIs + a `USER_CHAIN` pipeline on startup (calls back to the app's own `/mock-api/*`).
