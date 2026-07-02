-- API 编排平台 数据库初始化脚本

CREATE TABLE IF NOT EXISTS api_registry (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API 注册表';

CREATE TABLE IF NOT EXISTS pipeline_def (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    pipeline_name   VARCHAR(128)  NOT NULL COMMENT '编排名称',
    pipeline_code   VARCHAR(64)   NOT NULL UNIQUE COMMENT '编排唯一标识，也是产出的新 API 路径',
    pipeline_config JSON          NOT NULL COMMENT '编排拓扑 JSON（核心字段）',
    description     VARCHAR(512)  COMMENT '描述',
    status          TINYINT       DEFAULT 1 COMMENT '1-启用 0-禁用',
    created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编排定义表';

CREATE TABLE IF NOT EXISTS pipeline_exec_log (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行日志表';
