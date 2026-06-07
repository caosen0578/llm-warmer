package com.pab.ficc.ibp.modelgate.server.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.domain.dto.CreateEndpointRequest;
import com.pab.ficc.ibp.modelgate.server.domain.dto.UpdateEndpointRequest;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelEndpoint;
import com.pab.ficc.ibp.modelgate.server.domain.vo.EndpointVO;
import com.pab.ficc.ibp.modelgate.server.mapper.ModelEndpointMapper;
import com.pab.ficc.ibp.modelgate.server.service.ModelEndpointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ModelEndpointServiceImpl implements ModelEndpointService {

    private final ModelEndpointMapper endpointMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Long create(CreateEndpointRequest req) {
        ModelEndpoint endpoint = new ModelEndpoint();
        endpoint.setName(req.getName());
        endpoint.setBaseUrl(req.getBaseUrl());
        endpoint.setModelName(req.getModelName());
        endpoint.setApiKey(req.getApiKey());
        // httpMethod 统一转大写存储，兼容前端传 "post"/"POST"
        endpoint.setHttpMethod(req.getHttpMethod() != null ? req.getHttpMethod().toUpperCase() : "POST");
        endpoint.setRequestHeaders(req.getRequestHeaders());
        endpoint.setEnabled(1);
        endpoint.setCreatedAt(LocalDateTime.now());
        endpoint.setUpdatedAt(LocalDateTime.now());
        endpointMapper.insert(endpoint);
        return endpoint.getId();
    }

    @Override
    public void update(UpdateEndpointRequest req) {
        ModelEndpoint endpoint = requireEndpoint(req.getId());
        // 局部更新：只覆盖请求中非 null 的字段
        if (req.getName() != null)           endpoint.setName(req.getName());
        if (req.getBaseUrl() != null)        endpoint.setBaseUrl(req.getBaseUrl());
        if (req.getModelName() != null)      endpoint.setModelName(req.getModelName());
        if (req.getApiKey() != null)         endpoint.setApiKey(req.getApiKey());
        if (req.getHttpMethod() != null)     endpoint.setHttpMethod(req.getHttpMethod().toUpperCase());
        if (req.getRequestHeaders() != null) endpoint.setRequestHeaders(req.getRequestHeaders());
        if (req.getEnabled() != null)        endpoint.setEnabled(req.getEnabled());
        endpoint.setUpdatedAt(LocalDateTime.now());
        endpointMapper.updateById(endpoint);
    }

    @Override
    public void delete(Long id) {
        requireEndpoint(id);
        endpointMapper.deleteById(id);
    }

    @Override
    public EndpointVO getById(Long id) {
        return toVO(requireEndpoint(id));
    }

    @Override
    public List<EndpointVO> listAll() {
        return endpointMapper.selectList(null).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public ModelEndpoint getEntityById(Long id) {
        return requireEndpoint(id);
    }

    /** 查询端点，不存在则抛 404 业务异常 */
    private ModelEndpoint requireEndpoint(Long id) {
        ModelEndpoint endpoint = endpointMapper.selectById(id);
        if (endpoint == null) throw new BusinessException(404, "模型端点不存在: " + id);
        return endpoint;
    }

    /**
     * 将实体转为 VO。
     * 注意：apiKey 不出现在 VO 中，避免通过列表/详情接口泄露密钥。
     * headerCount 返回头的数量（而非具体内容），供前端展示"N 个自定义头"。
     */
    private EndpointVO toVO(ModelEndpoint e) {
        EndpointVO vo = new EndpointVO();
        vo.setId(e.getId());
        vo.setName(e.getName());
        vo.setBaseUrl(e.getBaseUrl());
        vo.setModelName(e.getModelName());
        vo.setHttpMethod(e.getHttpMethod() != null ? e.getHttpMethod() : "POST");
        vo.setHeaderCount(countHeaders(e.getRequestHeaders()));
        vo.setEnabled(e.getEnabled());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        return vo;
    }

    /** 解析 requestHeaders JSON，返回头的数量；解析失败返回 0 */
    private int countHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) return 0;
        try {
            JsonNode node = objectMapper.readTree(headersJson);
            return node.isObject() ? node.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
