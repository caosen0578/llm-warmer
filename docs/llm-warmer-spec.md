# llm-warmer 需求规范与迁移指引

> 本文档记录 llm-warmer 的完整需求、接口规范、技术决策及合并到 modelgate 时的迁移要点。
> 供内部团队接手、扩展、合并使用。

---

## 一、项目背景

内部部署了多个大语言模型，白天正常业务调用时 GPU 利用率不足 40%，tokens 产出偏低，效能团队要求治理。

**解决思路：** 在夜间或低峰期通过定时调度，向模型端点发起批量 HTTP 调用，提高 GPU 利用率和 tokens 产出。

---

## 二、功能模块

### 2.1 模型端点管理（model_endpoint）

| 字段 | 说明 |
|---|---|
| `base_url` | 完整请求地址，由用户填写，**不自动拼接路径** |
| `http_method` | GET 或 POST，用户选择 |
| `api_key` | 自动添加 `Authorization: Bearer {key}` 请求头 |
| `request_headers` | 自定义请求头，JSON 对象字符串，支持动态增删 |
| `model_name` | 仅展示用标签，不参与请求构造 |
| `enabled` | 0=禁用 1=启用 |

> `api_key` 不通过 VO 返回给前端，避免敏感信息泄露。

### 2.2 预热任务管理（warm_task）

| 字段 | 说明 |
|---|---|
| `cron_expr` | Cron 表达式，支持前端时间窗口模式自动生成 |
| `tps` | 每秒请求数（所有线程合计），由调度器控制速率 |
| `thread_count` | 并发线程数（最大同时在途请求数），独立控制并发 |
| `duration_seconds` | 持续时长（秒）；**-1 表示长期运行，手动停止** |
| `request_body` | 完整请求体 JSON |
| `status` | IDLE / RUNNING / STOPPED |

**TPS 与并发的实现原理：**
- `ScheduledExecutorService`（单线程）按 `1_000_000_000 / tps` 纳秒间隔提交任务 → 控制速率
- `FixedThreadPool(threadCount)` 限制同时飞行的请求数 → 控制并发
- 两者独立：TPS 控制发送频率，threadCount 控制排队深度
- 最优配置参考 Little's Law：`threadCount ≈ tps × 平均响应时间(秒)`

**Cron 时间窗口模式（前端辅助生成）：**
- 用户选择开始/结束时间，自动生成 `0 {mm} {HH} ? * MON-FRI` 格式
- 跨天处理：`endTotal <= startTotal` 时 `durationSeconds += 1440 * 60`

### 2.3 执行历史（task_execution）

每次任务执行写入一条记录，字段：`total_requests`、`success_count`、`fail_count`、`total_tokens`、`status`（RUNNING / SUCCESS / STOPPED / FAILED）。

### 2.4 测试请求

- 入口：新增/编辑预热任务弹窗内的「🧪 测试请求」按钮
- 接口：`POST /api/test/request`，body 含 `endpointId` + `requestBody`
- **必须完整记录**请求体、响应体、延迟、tokens
- 批量预热调用路径：**不记录请求体/响应体日志**（高频调用，无意义）

### 2.5 实时监控（Live Stats）

- 接口：`GET /api/tasks/live-stats?id={taskId}`
- 数据来自内存（`AtomicLong` 计数器），零 DB 开销
- 返回：总请求数、成功/失败数、成功率、当前 TPS、累计 tokens、已运行时长
- 最近 50 条调用明细（时间、延迟、tokens、错误信息），`ArrayDeque` 环形缓冲，最新在前
- 前端每秒轮询，任务停止后自动停止轮询

---

## 三、接口清单

### 模型端点（/api/endpoints）

| 方法 | 路径 | 说明 | 传参方式 |
|---|---|---|---|
| POST | `/api/endpoints/create` | 新增端点 | RequestBody |
| POST | `/api/endpoints/update` | 更新端点 | RequestBody（含 id） |
| POST | `/api/endpoints/delete` | 删除端点 | `?id=` |
| GET  | `/api/endpoints/detail` | 查询单个 | `?id=` |
| GET  | `/api/endpoints/list`   | 查询全部 | — |

### 预热任务（/api/tasks）

| 方法 | 路径 | 说明 | 传参方式 |
|---|---|---|---|
| POST | `/api/tasks/create`     | 新增任务 | RequestBody |
| POST | `/api/tasks/update`     | 更新任务 | RequestBody（含 id） |
| POST | `/api/tasks/delete`     | 删除任务 | `?id=` |
| GET  | `/api/tasks/detail`     | 查询单个 | `?id=` |
| GET  | `/api/tasks/list`       | 查询全部 | — |
| POST | `/api/tasks/trigger`    | 手动触发 | `?id=` |
| POST | `/api/tasks/stop`       | 手动停止 | `?id=` |
| GET  | `/api/tasks/running`    | 是否运行中 | `?id=` |
| GET  | `/api/tasks/live-stats` | 实时统计 | `?id=` |

### 执行历史（/api/executions）

| 方法 | 路径 | 说明 | 传参方式 |
|---|---|---|---|
| GET | `/api/executions/list` | 查询历史 | `?taskId=&limit=` |

### 测试（/api/test）

| 方法 | 路径 | 说明 | 传参方式 |
|---|---|---|---|
| POST | `/api/test/request` | 单次测试调用 | RequestBody |

---

## 四、技术规范

### 4.1 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot | 2.7.18 | 与 modelgate 保持一致 |
| Java | 17 | |
| MyBatis Plus | 3.5.9 | |
| Apollo | 2.3.0 | 配置中心 |
| OkHttp | 4.x（Boot 管理） | LLM HTTP 调用，连接池 128，maxRequestsPerHost 512 |
| springdoc-openapi-ui | 1.7.0 | Swagger UI，Spring Boot 2.x 对应版本 |
| com.pab.framework:redis | 内网统一版本 | 分布式调度锁，**禁止引入 spring-boot-starter-data-redis** |
| 数据源 | VenusDataSourceBean | 内网 TDSQL，排除 HikariCP |

### 4.2 Controller 接口规范

> **禁止使用 `@PathVariable`**，原因：接口路径录入 RBAC 权限表，路径含变量无法精确匹配。

| 操作 | 正确写法 | 错误写法 |
|---|---|---|
| 查询单条 | `GET /api/xxx/detail?id=1` | `GET /api/xxx/{id}` |
| 更新 | `POST /api/xxx/update`（body 含 id） | `PUT /api/xxx/{id}` |
| 删除 | `POST /api/xxx/delete?id=1` | `DELETE /api/xxx/{id}` |
| 列表 | `GET /api/xxx/list` | `GET /api/xxx` |
| 创建 | `POST /api/xxx/create` | `POST /api/xxx` |
| 动作类 | `POST /api/xxx/trigger?id=1` | `POST /api/xxx/{id}/trigger` |

Update 类 DTO 必须包含 `@NotNull private Long id` 字段。

### 4.3 日志规范

- **批量预热调用**：不记录 request body / response body，仅记录统计数据（高频无意义）
- **测试请求**：完整记录
  ```
  [TestRequest] endpoint={}({}) url={} requestBody={}
  [TestRequest] SUCCESS latency={}ms tokens={} response={}
  [TestRequest] FAILED latency={}ms error={}
  ```
- **调度引擎日志前缀**

  | 前缀 | 来源 |
  |---|---|
  | `[Engine]` | WarmExecutionEngine（执行、停止、锁释放） |
  | `[Scheduler]` | DynamicTaskScheduler（cron 注册、锁竞争） |

### 4.4 返回值规范

统一使用 `Result<T>` 包装：

```json
{ "code": 0, "message": "success", "data": {}, "timestamp": 1717000000000 }
```

`code=0` 为成功，非 0 为失败（400=业务异常，500=系统异常）。

### 4.5 编码规范

- 所有源文件统一 **UTF-8 无 BOM**
- 项目根目录已有 `.editorconfig` 约束，IDEA / VSCode 均可自动生效
- `pom.xml` 已声明 `project.build.sourceEncoding=UTF-8`

---

## 五、多节点部署方案（K8s 双活）

### 5.1 Zone 级隔离（环境变量门控）

```yaml
# 跑批区 Deployment — 开启调度
env:
  - name: WARM_SCHEDULER_ENABLED
    value: "true"

# 业务区 Deployment — 不设置或设为 false
# 业务区 Pod 完全不注册 cron，不参与 Redis 锁竞争
```

application.yml 对应配置：
```yaml
warm:
  scheduler:
    enabled: ${WARM_SCHEDULER_ENABLED:false}
```

### 5.2 同区多节点均分（Redis SETNX 分布式锁）

**设计原则：**
- 每个 Pod 启动时生成 UUID（`DynamicTaskScheduler.NODE_ID`），不依赖 IP，重启后自然变更
- cron 触发时，每个 Pod 竞争 `SETNX warm:task:lock:{taskId}`
- 抢到锁的 Pod 执行，其余静默跳过 → 多任务多 Pod 自然均摊
- 任务结束时用 Lua 脚本原子释放（仅删除属于自己的 key，防误删）
- Pod 崩溃时，key 依靠 TTL 自动过期，下次 cron 由其他 Pod 接管

**锁 TTL 计算：**
- 固定时长任务：`durationSeconds + 60`（60s 缓冲，防止任务刚结束锁就被重抢）
- 长期运行任务（`-1`）：`86400`（24h），由 `stop()` / 优雅停机主动释放

**Lua 原子释放脚本：**
```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0   -- 非本节点持有，跳过
end
```

**手动 trigger 接口**：绕过锁直接执行，用于调试，不影响正常调度逻辑。

### 5.3 Redis Key 规范

```
warm:task:lock:{taskId}    分布式执行锁
                           TTL = durationSeconds + 60（长期任务 = 86400）
                           Value = Pod 启动时生成的 UUID
```

Redis 连接由 `com.pab.framework:redis` 框架管理，配置通过 Apollo 对应命名空间下发，**无需在 application.yml 中配置连接信息**。

---

## 六、数据库表结构

```sql
-- 模型端点
CREATE TABLE IF NOT EXISTS model_endpoint (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    name             VARCHAR(100)  NOT NULL COMMENT '端点显示名称',
    base_url         VARCHAR(1000) NOT NULL COMMENT '完整请求地址（不自动拼接路径）',
    model_name       VARCHAR(100)           COMMENT '模型名称标签（仅展示）',
    api_key          VARCHAR(500)           COMMENT 'API Key（自动添加 Authorization: Bearer 头）',
    http_method      VARCHAR(10)   NOT NULL DEFAULT 'POST' COMMENT 'GET 或 POST',
    request_headers  TEXT                   COMMENT '自定义请求头，JSON 对象字符串',
    enabled          TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '0=禁用 1=启用',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 预热任务
CREATE TABLE IF NOT EXISTS warm_task (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name             VARCHAR(100) NOT NULL COMMENT '任务名称',
    endpoint_id      BIGINT       NOT NULL COMMENT '关联模型端点 ID',
    cron_expr        VARCHAR(100) NOT NULL COMMENT 'Cron 表达式',
    tps              INT          NOT NULL DEFAULT 1   COMMENT '每秒总请求数',
    thread_count     INT          NOT NULL DEFAULT 1   COMMENT '并发线程数',
    duration_seconds INT          NOT NULL DEFAULT 3600 COMMENT '持续时长（秒），-1=长期运行',
    request_body     TEXT                  COMMENT '完整请求体 JSON',
    enabled          TINYINT(1)   NOT NULL DEFAULT 1   COMMENT '0=禁用 1=启用',
    status           VARCHAR(20)  NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE/RUNNING/STOPPED',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_endpoint (endpoint_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 执行历史
CREATE TABLE IF NOT EXISTS task_execution (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_id        BIGINT      NOT NULL COMMENT '关联任务 ID',
    start_time     DATETIME    NOT NULL COMMENT '执行开始时间',
    end_time       DATETIME             COMMENT '执行结束时间',
    total_requests BIGINT      NOT NULL DEFAULT 0 COMMENT '总请求次数',
    success_count  BIGINT      NOT NULL DEFAULT 0 COMMENT '成功次数',
    fail_count     BIGINT      NOT NULL DEFAULT 0 COMMENT '失败次数',
    total_tokens   BIGINT      NOT NULL DEFAULT 0 COMMENT '累计 token 产出量',
    status         VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/STOPPED/FAILED',
    error_msg      VARCHAR(500)         COMMENT '异常信息',
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_task_id (task_id),
    INDEX idx_start_time (start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

---

## 七、合并到 modelgate 指引

### 7.1 当前包结构

代码已使用 `com.pab.ficc.ibp.modelgate.server` 作为根包，按层平铺：

```
com.pab.ficc.ibp.modelgate.server
├── controller/          WarmTaskController, ExecutionController, TestController, ModelEndpointController
├── service/             WarmTaskService, ModelEndpointService（接口）
│   └── impl/            WarmTaskServiceImpl, ModelEndpointServiceImpl
├── domain/
│   ├── entity/          WarmTask, TaskExecution, ModelEndpoint
│   ├── dto/             CreateTaskRequest, UpdateTaskRequest, CreateEndpointRequest, UpdateEndpointRequest, TestRequestDTO
│   └── vo/              WarmTaskVO, TaskExecutionVO, EndpointVO, LiveStatsVO, TestResponseVO
├── mapper/              WarmTaskMapper, TaskExecutionMapper, ModelEndpointMapper
├── engine/              WarmExecutionEngine, LlmHttpClient, CallResult
├── scheduler/           DynamicTaskScheduler
├── config/              SchedulerConfig, HttpClientConfig, TdsqlMybatisPlusConfig, ServiceConfiguration
└── common/              Result, BusinessException, GlobalExceptionHandler
```

### 7.2 ModelEndpoint 实体合并

llm-warmer 的 `model_endpoint` 与 modelgate 的端点概念高度重合，合并时以 modelgate 的实体为基准：

```sql
-- 若 modelgate 的 model_endpoint 缺少以下字段，执行补充 DDL
ALTER TABLE model_endpoint
    ADD COLUMN http_method     VARCHAR(10) NOT NULL DEFAULT 'POST' COMMENT 'GET 或 POST',
    ADD COLUMN request_headers TEXT COMMENT '自定义请求头，JSON 对象字符串';
```

`WarmTaskService` / `WarmExecutionEngine` 直接引用 modelgate 的 `ModelEndpointMapper`，不新增 mapper。

### 7.3 新增 Bean 清单

合并时 modelgate 需注册以下 Bean：

```java
// SchedulerConfig.java — cron 调度线程池
@Bean
public ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
    s.setPoolSize(20);
    s.setThreadNamePrefix("warm-cron-");
    s.setWaitForTasksToCompleteOnShutdown(false);
    s.setAwaitTerminationSeconds(5);
    return s;
}

// HttpClientConfig.java — OkHttp（高并发，连接池需放开）
@Bean
public OkHttpClient okHttpClient() {
    Dispatcher dispatcher = new Dispatcher();
    dispatcher.setMaxRequests(512);
    dispatcher.setMaxRequestsPerHost(512);
    return new OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(new ConnectionPool(128, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();
}
```

> 若 modelgate 已有 OkHttpClient Bean，确认连接池配置能满足预热高并发，或单独注册一个 `@Bean("warmHttpClient")` 并在 `LlmHttpClient` 中用 `@Qualifier` 区分。

### 7.4 Swagger 分组（可选）

```java
@Bean
public GroupedOpenApi warmerApiGroup() {
    return GroupedOpenApi.builder()
            .group("llm-warmer")
            .pathsToMatch("/api/tasks/**", "/api/executions/**", "/api/test/**")
            .build();
}
```

### 7.5 application.yml 新增配置

```yaml
warm:
  scheduler:
    enabled: ${WARM_SCHEDULER_ENABLED:false}
```

Redis 连接信息无需在此配置，由 `com.pab.framework:redis` 框架从 Apollo 读取。

### 7.6 主类注解检查

```java
@SpringBootApplication       // 扫描根包，engine/ scheduler/ 自动覆盖
@EnableApolloConfig          // modelgate 已有，无需重复
@EnableScheduling            // 必须添加，DynamicTaskScheduler 依赖
@EnableAsync                 // 按需
public class ModelGateApplication { ... }
```

### 7.7 迁移检查清单

**数据库**
- [ ] 执行 `warm_task`、`task_execution` 表 DDL
- [ ] 检查 `model_endpoint` 是否需要补充 `http_method`、`request_headers` 字段

**中间件**
- [ ] 确认 `com.pab.framework:redis` 依赖已在 modelgate pom 中
- [ ] Redis 连接配置已在 Apollo 对应命名空间下发

**代码**
- [ ] `SchedulerConfig` Bean 注册（`ThreadPoolTaskScheduler`）
- [ ] `HttpClientConfig` Bean 注册或复用（注意连接池上限）
- [ ] 主类添加 `@EnableScheduling`
- [ ] `@MapperScan` 覆盖新增的 mapper 包路径

**运维**
- [ ] 跑批区 K8s Deployment 添加 `WARM_SCHEDULER_ENABLED=true` 环境变量
- [ ] 业务区 Deployment 不设置该变量（默认 false）
- [ ] 确认跑批区与业务区共用同一个 Redis 集群（锁需要跨节点可见）

**可选**
- [ ] Swagger `GroupedOpenApi` 分组 Bean 注册

---

## 八、运维注意事项

### 8.1 优雅停机

`WarmExecutionEngine` 实现了 `@PreDestroy`：Pod 停机时会自动调用 `stop()` 终止所有运行中任务，将 `task_execution.status` 更新为 `STOPPED`，并释放 Redis 锁。

无需额外配置，确保 K8s `terminationGracePeriodSeconds` ≥ 30s 即可。

### 8.2 长期任务的 Redis 锁

`durationSeconds=-1` 的任务锁 TTL 为 24h。若节点异常未触发 `@PreDestroy`，最多 24h 后锁自动释放，下次 cron 触发时其他节点可接管。

### 8.3 TPS 与线程数配置建议

| 模型响应时间 | 建议 threadCount |
|---|---|
| 1s | = tps |
| 2s | = tps × 2 |
| 5s | = tps × 5 |

`threadCount` 过小会导致队列积压，实际 TPS 低于配置值；过大浪费内存（JVM 默认每线程 ~1MB 栈）。

### 8.4 OkHttp 连接池

已配置 `maxRequestsPerHost=512`，连接池 128 个连接。若单个模型端点 TPS 很高，可适当调大 `ConnectionPool` 第一个参数（最大空闲连接数）。

---

## 九、前端 UI 规范

- 风格：深靛蓝侧边栏（`#1e1b4b → #312e81`），主色 `#6366f1`，与 modelgate 一致
- Mock 数据回退：服务不可达时自动加载模拟数据，顶部显示 MOCK 徽章，**写操作在 Mock 模式下弹提示不执行**
- API tooltip：所有调用后端的按钮加 `data-api="METHOD /path"` 属性，hover 展示接口路径
- 实时监控面板：1 秒轮询 `live-stats` 接口，任务停止后自动停止轮询，面板显示最近 50 条请求明细
