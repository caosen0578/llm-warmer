package com.pab.ficc.ibp.modelgate.server.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class CreateEndpointRequest {

    @NotBlank(message = "端点名称不能为空")
    private String name;

    @NotBlank(message = "请求地址不能为空")
    private String baseUrl;

    /** 显示用标签，可选 */
    private String modelName;

    private String apiKey;

    /** GET 或 POST，默认 POST */
    private String httpMethod = "POST";

    /** 自定义请求头，JSON 对象字符串 */
    private String requestHeaders;
}
