package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.domain.dto.CreateEndpointRequest;
import com.pab.ficc.ibp.modelgate.server.domain.dto.UpdateEndpointRequest;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelEndpoint;
import com.pab.ficc.ibp.modelgate.server.domain.vo.EndpointVO;

import java.util.List;

public interface ModelEndpointService {

    Long create(CreateEndpointRequest req);

    void update(UpdateEndpointRequest req);

    void delete(Long id);

    EndpointVO getById(Long id);

    List<EndpointVO> listAll();

    ModelEndpoint getEntityById(Long id);
}
