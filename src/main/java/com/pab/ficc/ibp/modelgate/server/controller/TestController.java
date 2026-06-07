package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.dto.TestRequestDTO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelEndpoint;
import com.pab.ficc.ibp.modelgate.server.domain.vo.TestResponseVO;
import com.pab.ficc.ibp.modelgate.server.engine.CallResult;
import com.pab.ficc.ibp.modelgate.server.engine.LlmHttpClient;
import com.pab.ficc.ibp.modelgate.server.service.ModelEndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@Slf4j
@Tag(name = "测试接口")
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {

    private final LlmHttpClient llmHttpClient;
    private final ModelEndpointService endpointService;

    @Operation(summary = "发起单次测试请求，完整记录请求体和响应内容")
    @PostMapping("/request")
    public Result<TestResponseVO> testRequest(@Valid @RequestBody TestRequestDTO req) {
        ModelEndpoint endpoint = endpointService.getEntityById(req.getEndpointId());

        log.info("[TestRequest] endpoint={}({}) url={} requestBody={}",
                endpoint.getId(), endpoint.getName(), endpoint.getBaseUrl(), req.getRequestBody());

        CallResult result = llmHttpClient.call(endpoint, req.getRequestBody());

        if (result.success()) {
            log.info("[TestRequest] SUCCESS latency={}ms tokens={} response={}",
                    result.latencyMs(), result.totalTokens(), result.responseBody());
        } else {
            log.warn("[TestRequest] FAILED latency={}ms error={}",
                    result.latencyMs(), result.errorMessage());
        }

        TestResponseVO vo = new TestResponseVO();
        vo.setSuccess(result.success());
        vo.setTotalTokens(result.totalTokens());
        vo.setResponseBody(result.responseBody());
        vo.setLatencyMs(result.latencyMs());
        vo.setErrorMessage(result.errorMessage());
        return Result.ok(vo);
    }
}
