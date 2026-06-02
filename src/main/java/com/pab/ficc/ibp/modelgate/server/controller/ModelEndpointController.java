package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.dto.CreateEndpointRequest;
import com.pab.ficc.ibp.modelgate.server.domain.dto.UpdateEndpointRequest;
import com.pab.ficc.ibp.modelgate.server.domain.vo.EndpointVO;
import com.pab.ficc.ibp.modelgate.server.service.ModelEndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@Tag(name = "模型端点管理")
@RestController
@RequestMapping("/api/endpoints")
@RequiredArgsConstructor
public class ModelEndpointController {

    private final ModelEndpointService endpointService;

    @Operation(summary = "新增模型端点")
    @PostMapping("/create")
    public Result<Long> create(@Valid @RequestBody CreateEndpointRequest req) {
        return Result.ok(endpointService.create(req));
    }

    @Operation(summary = "更新模型端点（id 放在请求体中�?)
    @PostMapping("/update")
    public Result<Void> update(@Valid @RequestBody UpdateEndpointRequest req) {
        endpointService.update(req);
        return Result.ok();
    }

    @Operation(summary = "删除模型端点")
    @PostMapping("/delete")
    public Result<Void> delete(@RequestParam Long id) {
        endpointService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "查询单个端点")
    @GetMapping("/detail")
    public Result<EndpointVO> detail(@RequestParam Long id) {
        return Result.ok(endpointService.getById(id));
    }

    @Operation(summary = "查询所有端�?)
    @GetMapping("/list")
    public Result<List<EndpointVO>> list() {
        return Result.ok(endpointService.listAll());
    }
}
