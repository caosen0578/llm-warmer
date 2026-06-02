package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.domain.dto.CreateTaskRequest;
import com.pab.ficc.ibp.modelgate.server.domain.dto.UpdateTaskRequest;
import com.pab.ficc.ibp.modelgate.server.domain.entity.WarmTask;
import com.pab.ficc.ibp.modelgate.server.domain.vo.WarmTaskVO;

import java.util.List;

public interface WarmTaskService {

    Long create(CreateTaskRequest req);

    void update(UpdateTaskRequest req);

    void delete(Long id);

    WarmTaskVO getById(Long id);

    List<WarmTaskVO> listAll();

    List<WarmTask> listEnabledEntities();

    void updateStatus(Long id, String status);
}
