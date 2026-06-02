package com.pab.ficc.ibp.modelgate.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_endpoint")
public class ModelEndpoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 完整请求地址，由调用方填写，不拼接任何路径 */
    private String baseUrl;

    /** 显示用的模型名称标签，可选 */
    private String modelName;

    private String apiKey;

    /** GET 或 POST */
    private String httpMethod;

    /** 自定义请求头，JSON 对象字符串，如 {"X-Api-Version":"2024-01"} */
    private String requestHeaders;

    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
