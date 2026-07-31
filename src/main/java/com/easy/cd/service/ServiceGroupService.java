package com.easy.cd.service;

import com.easy.cd.dto.ServiceGroupCreateDTO;
import com.easy.cd.dto.ServiceGroupUpdateDTO;
import com.easy.cd.dto.ServiceLayoutUpdateDTO;
import com.easy.cd.vo.ServiceGroupVO;

import java.util.List;

public interface ServiceGroupService {

    List<ServiceGroupVO> listByEnvironment(Long environmentId);

    ServiceGroupVO create(ServiceGroupCreateDTO createDTO);

    ServiceGroupVO update(Long id, ServiceGroupUpdateDTO updateDTO);

    void delete(Long id);

    void updateLayout(ServiceLayoutUpdateDTO layoutDTO);

    int nextUngroupedSortOrder(Long environmentId);
}
