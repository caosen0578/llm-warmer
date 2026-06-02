-- llm-warmer 数据库初始化脚本

CREATE TABLE IF NOT EXISTS model_endpoint (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name             VARCHAR(100) NOT NULL COMMENT '端点显示名称',
    base_url         VARCHAR(1000) NOT NULL COMMENT '完整请求地址（由调用方填写，不自动拼接路径）',
    model_name       VARCHAR(100)           COMMENT '模型名称标签（可选，仅用于展示）',
    api_key          VARCHAR(500)           COMMENT 'API Key（自动添加 Authorization: Bearer 头）',
    http_method      VARCHAR(10)  NOT NULL DEFAULT 'POST' COMMENT 'HTTP 方法：GET 或 POST',
    request_headers  TEXT                   COMMENT '自定义请求头，JSON 对象字符串',
    enabled          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0=禁用 1=启用',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型端点配置';


CREATE TABLE IF NOT EXISTS warm_task (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name             VARCHAR(100) NOT NULL COMMENT '任务名称',
    endpoint_id      BIGINT       NOT NULL COMMENT '关联模型端点 ID',
    cron_expr        VARCHAR(100) NOT NULL COMMENT 'Cron 表达式（触发开始时间），如 0 0 22 * * ?',
    tps              INT          NOT NULL DEFAULT 1   COMMENT '每秒总请求数（所有线程合计）',
    thread_count     INT          NOT NULL DEFAULT 1   COMMENT '并发线程数（最大同时在途请求数）',
    duration_seconds INT          NOT NULL DEFAULT 3600 COMMENT '持续运行时长（秒），-1 表示长期运行直到手动停止',
    request_body     TEXT                  COMMENT '完整请求体 JSON 字符串',
    enabled          TINYINT(1)   NOT NULL DEFAULT 1   COMMENT '0=禁用 1=启用',
    status           VARCHAR(20)  NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE/RUNNING/STOPPED',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_endpoint (endpoint_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预热调度任务配置';


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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务执行历史记录';
