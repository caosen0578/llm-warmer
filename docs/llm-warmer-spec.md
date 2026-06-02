# llm-warmer 需求规范与迁移指引

> 本文档记录 llm-warmer 项目的完整需求、技术规范及合并到 modelgate 时的迁移要点。

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

### 2.2 预热任务管理（warm_task）

| 字段 | 说明 |
|---|---|
| `cron_expr` | Cron 表达式，支持前端时间窗口模式自动生成 |
| `tps` | 每秒请求数（所有线程合计），由调度器控制速率 |
| `thread_count` | 并发线程数（最大同时在途请求数），独立控制并发 |
| `duration_seconds` | 持续时长（秒）；**-1 表示长期运行，手动停止** |
| `request_body` | 完整请求体 JSON，**原名 prompt_text，已更名** |
| `status` | IDLE / RUNNING / STOPPED |

**TPS 与并发的实现原理：**
- `ScheduledExecutorService`（单线程）按 `1_000_000_000 / tps` 纳秒间隔提交任务 → 控制速率
- `FixedThreadPool(threadCount)` 限制同时飞行的请求数 → 控制并发
- 最优配置参考 Little's Law：`threadCount ≈ tps × 平均响应时间(秒)`

**Cron 时间窗口模式（前端）：**
- 用户选择开始/结束时间，自动生成 `0 {mm} {HH} ? * MON-FRI` 格式
- 跨天处理：`endTotal <= startTotal` 时 `durationSeconds += 1440 * 60`

### 2.3 执行历史（task_execution）

每次任务执行写入一条记录，字段：`total_requests`、`success_count`、`fail_count`、`total_tokens`、`status`（RUNNING/SUCCESS/STOPPED/FAILED）。

### 2.4 测试请求

- 入口：新增/编辑预热任务弹窗内的「测试请求」按钮
- 接口：`POST /api/test/request`，body 含 `endpointId` + `requestBody`
- **必须完整记录**请求体、响应体、延迟、tokens（与批量预热的无日志策略相反）
- 批量预热调用路径：**不记录请求体/响应体日志**（高频调用，无意义）

### 2.5 实时监控（Live Stats）

- 接口：`GET /api/tasks/live-stats?id={taskId}`
- 数据来自内存（`AtomicLong` 计数器），零 DB 开销
- 返回：总请求数、成功/失败数、成功率、当前 TPS、累计 tokens、已运行时长
- 最近 50 条调用明细（时间、延迟、tokens、错误信息），`ArrayDeque` 环形缓冲
- 前端每秒轮询，任务结束后自动停止轮询

---

## 三、技术规范

### 3.1 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot | 2.7.18 | 与 modelgate 保持一致 |
| Java | 17 | |
| MyBatis Plus | 3.5.9 | |
| Apollo | 2.3.0 | 配置中心 |
| OkHttp | 4.x（Boot 管理） | LLM HTTP 调用 |
| springdoc-openapi-ui | 1.7.0 | Swagger UI，Spring Boot 2.x 对应版本 |
| spring-data-redis | Boot 管理 | 分布式调度锁 |
| 数据源 | VenusDataSourceBean | 内网 TDSQL，排除 HikariCP |

### 3.2 Controller 接口规范

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

### 3.3 日志规范

- **批量预热调用**：不记录 request body / response body，仅记录统计数据
- **测试请求**：完整记录，格式：
  ```
  [TestRequest] endpoint={}({}) url={} requestBody={}
  [TestRequest] SUCCESS latency={}ms tokens={} response={}
  [TestRequest] FAILED latency={}ms error={}
  ```
- **调度引擎**：使用 `[Engine]`、`[Scheduler]`、`[NodeRegistry]` 前缀区分来源

### 3.4 返回值规范

统一使用 `Result<T>` 包装，与 modelgate 共用同一个类。

---

## 四、多节点部署方案（K8s 双活）

### 4.1 Zone 级隔离（环境变量门控）

```yaml
# 跑批区 Deployment — 开启调度
env:
  - name: WARM_SCHEDULER_ENABLED
    value: "true"

# 业务区 Deployment — 不设置或设为 false
# 业务区 Pod 完全不注册 cron，Redis 操作也不触发
```

对应配置：
```yaml
warm:
  scheduler:
    enabled: ${WARM_SCHEDULER_ENABLED:false}
```

### 4.2 同区多节点均分（Redis SETNX 分布式锁）

**设计原则：**
- 每个 Pod 启动时生成一个 UUID（`DynamicTaskScheduler.NODE_ID`），不依赖 IP
- cron 触发时，每个 Pod 竞争 `SETNX warm:task:lock:{taskId}`
- 抢到锁的 Pod 执行，其余静默跳过
- 任务结束时用 Lua 脚本原子释放锁（仅删除属于自己的 key）
- Pod 崩溃时 key 依靠 TTL 自动过期，下次 cron 由其他 Pod 接管

**锁 TTL 计算：**
- 固定时长任务：`durationSeconds + 60`（60s 缓冲）
- 长期运行任务（-1）：`86400`（24h），靠 `stop()` 主动释放

**Lua 释放脚本（防误删）：**
```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

**手动 trigger 接口**：不经过锁，直接执行，适用于调试和临时触发。

### 4.3 Redis Key 规范

```
warm:task:lock:{taskId}     分布式执行锁，TTL = durationSeconds + 60
```

---

## 五、数据库表结构

```sql
-- 模型端点
CREATE TABLE model_endpoint (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(100) NOT NULL,
    base_url         VARCHAR(1000) NOT NULL,
    model_name       VARCHAR(100),
    api_key          VARCHAR(500),
    http_method      VARCHAR(10)  NOT NULL DEFAULT 'POST',
    request_headers  TEXT,                          -- JSON 对象字符串
    enabled          TINYINT(1)   NOT NULL DEFAULT 1,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

-- 预热任务
CREATE TABLE warm_task (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(100) NOT NULL,
    endpoint_id      BIGINT       NOT NULL,
    cron_expr        VARCHAR(100) NOT NULL,
    tps              INT          NOT NULL DEFAULT 1,
    thread_count     INT          NOT NULL DEFAULT 1,
    duration_seconds INT          NOT NULL DEFAULT 3600,  -- -1 = 长期运行
    request_body     TEXT,
    enabled          TINYINT(1)   NOT NULL DEFAULT 1,
    status           VARCHAR(20)  NOT NULL DEFAULT 'IDLE',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

-- 执行历史
CREATE TABLE task_execution (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    task_id        BIGINT      NOT NULL,
    start_time     DATETIME    NOT NULL,
    end_time       DATETIME,
    total_requests BIGINT      NOT NULL DEFAULT 0,
    success_count  BIGINT      NOT NULL DEFAULT 0,
    fail_count     BIGINT      NOT NULL DEFAULT 0,
    total_tokens   BIGINT      NOT NULL DEFAULT 0,
    status         VARCHAR(20) NOT NULL DEFAULT 'RUNNING',  -- RUNNING/SUCCESS/STOPPED/FAILED
    error_msg      VARCHAR(500),
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_task_id (task_id),
    INDEX idx_start_time (start_time)
);
```

---

## 六、合并到 modelgate 指引

### 6.1 推荐包结构

```
com.pab.ficc.ibp.modelgate.server
├── controller/
│   ├── EndpointController.java        ← modelgate 原有
│   ├── WarmTaskController.java        ← 迁入
│   ├── ExecutionController.java       ← 迁入
│   └── TestController.java            ← 迁入
├── service/
│   ├── endpoint/                      ← modelgate 原有
│   └── warmer/                        ← 迁入（WarmTaskService 等）
├── domain/
│   ├── entity/
│   │   ├── ModelEndpoint.java         ← 合并共用（见下文）
│   │   ├── WarmTask.java              ← 迁入
│   │   └── TaskExecution.java         ← 迁入
│   ├── dto/
│   │   ├── endpoint/
│   │   └── task/                      ← 迁入
│   └── vo/
│       ├── WarmTaskVO.java            ← 迁入
│       ├── LiveStatsVO.java           ← 迁入
│       └── TestResponseVO.java        ← 迁入
├── mapper/
│   ├── WarmTaskMapper.java            ← 迁入
│   └── TaskExecutionMapper.java       ← 迁入
├── engine/                            ← 整目录迁入
│   ├── WarmExecutionEngine.java
│   ├── LlmHttpClient.java
│   └── CallResult.java
├── scheduler/                         ← 整目录迁入
│   └── DynamicTaskScheduler.java
├── config/
│   ├── ThreadPoolConfig.java          ← modelgate 原有，新增 warm scheduler bean
│   └── ...
└── common/                            ← 共用，Result/BusinessException 不重复引入
```

### 6.2 ModelEndpoint 实体合并

llm-warmer 的 `model_endpoint` 表与 modelgate 的端点概念高度重合，合并时：

- 以 modelgate 的 `ModelEndpoint` 实体为基准
- 检查是否缺少 `http_method`、`request_headers` 字段，若缺少执行 DDL：
  ```sql
  ALTER TABLE model_endpoint
      ADD COLUMN http_method     VARCHAR(10) NOT NULL DEFAULT 'POST',
      ADD COLUMN request_headers TEXT;
  ```
- `WarmTaskService` / `WarmExecutionEngine` 直接引用 modelgate 的 `ModelEndpointMapper`，不新增 mapper

### 6.3 ThreadPoolTaskScheduler Bean

`DynamicTaskScheduler` 依赖 `ThreadPoolTaskScheduler`，需在 modelgate 的 config 中注册：

```java
@Bean
public ThreadPoolTaskScheduler warmTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("warm-cron-");
    scheduler.initialize();
    return scheduler;
}
```

### 6.4 Swagger 分组（可选）

```java
@Bean
public GroupedOpenApi warmerApiGroup() {
    return GroupedOpenApi.builder()
            .group("llm-warmer")
            .pathsToMatch("/api/tasks/**", "/api/executions/**", "/api/test/**")
            .build();
}
```

### 6.5 application.yml 新增配置

```yaml
spring:
  redis:
    host: ${REDIS_HOST:127.0.0.1}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:}
    database: ${REDIS_DB:0}

warm:
  scheduler:
    enabled: ${WARM_SCHEDULER_ENABLED:false}
```

### 6.6 主类注解检查

```java
@SpringBootApplication          // 扫描范围自动覆盖 engine/ scheduler/
@EnableApolloConfig             // modelgate 已有，无需重复
@EnableScheduling               // 必须添加，支持 @Scheduled 心跳
@EnableAsync                    // 按需添加
public class ModelGateApplication { ... }
```

### 6.7 迁移检查清单

- [ ] `warm_task`、`task_execution` 表 DDL 在目标环境执行
- [ ] `model_endpoint` 表补充 `http_method`、`request_headers` 字段
- [ ] Redis 连接配置在 Apollo 对应命名空间下发
- [ ] 跑批区 K8s Deployment 添加 `WARM_SCHEDULER_ENABLED=true` 环境变量
- [ ] `ThreadPoolTaskScheduler` Bean 注册
- [ ] Swagger 分组 Bean 注册（可选）
- [ ] `@EnableScheduling` 加到主类
- [ ] `LlmHttpClient` 的 OkHttp 实例与 modelgate 现有 HTTP 客户端确认是否合并

---

## 七、前端 UI 规范

- 风格：深靛蓝侧边栏（`#1e1b4b → #312e81`），主色 `#6366f1`，与 modelgate 一致
- Mock 数据回退：服务不可达时自动加载模拟数据，顶部显示 MOCK 徽章
- API tooltip：所有调用后端的按钮加 `data-api="METHOD /path"` 属性，hover 展示接口路径
- 实时监控面板：1 秒轮询 `live-stats` 接口，任务停止后自动停止轮询
