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
        if (req.getName() != null) endpoint.setName(req.getName());
        if (req.getBaseUrl() != null) endpoint.setBaseUrl(req.getBaseUrl());
        if (req.getModelName() != null) endpoint.setModelName(req.getModelName());
        if (req.getApiKey() != null) endpoint.setApiKey(req.getApiKey());
        if (req.getHttpMethod() != null) endpoint.setHttpMethod(req.getHttpMethod().toUpperCase());
        if (req.getRequestHeaders() != null) endpoint.setRequestHeaders(req.getRequestHeaders());
        if (req.getEnabled() != null) endpoint.setEnabled(req.getEnabled());
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

    private ModelEndpoint requireEndpoint(Long id) {
        ModelEndpoint endpoint = endpointMapper.selectById(id);
        if (endpoint == null) throw new BusinessException(404, "模型端点不存在: " + id);
        return endpoint;
    }

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
