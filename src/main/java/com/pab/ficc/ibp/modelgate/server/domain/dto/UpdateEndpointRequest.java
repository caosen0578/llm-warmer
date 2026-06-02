package com.pab.ficc.ibp.modelgate.server.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class UpdateEndpointRequest {

    @NotNull(message = "端点 ID 不能为空")
    private Long id;

    private String name;
    private String baseUrl;
    private String modelName;
    private String apiKey;
    /** GET 或 POST */
    private String httpMethod;
    /** 自定义请求头，JSON 对象字符串 */
    private String requestHeaders;
    /** 0=禁用 1=启用 */
    private Integer enabled;
}
