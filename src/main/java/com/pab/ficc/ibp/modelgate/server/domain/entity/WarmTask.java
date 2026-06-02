package com.pab.ficc.ibp.modelgate.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("warm_task")
public class WarmTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Long endpointId;

    /** Cron 表达式（触发开始时间），如 "0 0 22 * * ?" */
    private String cronExpr;

    /** 每秒总请求数（所有线程合计） */
    private Integer tps;

    /** 并发线程数（最大同时在途请求数） */
    private Integer threadCount;

    /**
     * 每次触发后持续运行时长（秒）。
     * -1 表示长期运行，直到手动停止。
     */
    private Integer durationSeconds;

    /** 发送给模型的完整请求体（JSON 字符串） */
    private String requestBody;

    /** 0=禁用 1=启用 */
    private Integer enabled;

    /** IDLE / RUNNING / STOPPED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
