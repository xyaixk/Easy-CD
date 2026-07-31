package com.easy.cd.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.easy.cd.dto.ServiceGroupCreateDTO;
import com.easy.cd.dto.ServiceGroupUpdateDTO;
import com.easy.cd.dto.ServiceLayoutUpdateDTO;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.entity.ServiceGroup;
import com.easy.cd.exception.BusinessException;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ServiceGroupMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.service.ServiceGroupService;
import com.easy.cd.vo.ServiceGroupVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceGroupServiceImpl implements ServiceGroupService {

    private static final int MAX_GROUP_NAME_LENGTH = 50;

    private final ServiceGroupMapper serviceGroupMapper;
    private final ServiceMapper serviceMapper;
    private final EnvironmentMapper environmentMapper;

    @Override
    public List<ServiceGroupVO> listByEnvironment(Long environmentId) {
        requireEnvironment(environmentId);
        return listGroupEntities(environmentId).stream()
                .map(ServiceGroupVO::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceGroupVO create(ServiceGroupCreateDTO createDTO) {
        if (createDTO == null) {
            throw new BusinessException("分组参数不能为空");
        }
        Long environmentId = createDTO.getEnvironmentId();
        requireEnvironment(environmentId);
        String name = normalizeName(createDTO.getName());
        validateNameUnique(environmentId, null, name);

        int nextSortOrder = 0;
        List<ServiceGroup> groups = listGroupEntities(environmentId);
        if (!groups.isEmpty()) {
            ServiceGroup lastGroup = groups.get(groups.size() - 1);
            nextSortOrder = safeSortOrder(lastGroup.getSortOrder()) + 1;
        }

        LocalDateTime now = LocalDateTime.now();
        ServiceGroup group = new ServiceGroup();
        group.setEnvironmentId(environmentId);
        group.setName(name);
        group.setSortOrder(nextSortOrder);
        group.setCreatedTime(now);
        group.setUpdatedTime(now);
        serviceGroupMapper.insert(group);
        return ServiceGroupVO.from(group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceGroupVO update(Long id, ServiceGroupUpdateDTO updateDTO) {
        ServiceGroup group = requireGroup(id);
        if (updateDTO == null) {
            throw new BusinessException("分组参数不能为空");
        }
        String name = normalizeName(updateDTO.getName());
        validateNameUnique(group.getEnvironmentId(), id, name);

        group.setName(name);
        group.setUpdatedTime(LocalDateTime.now());
        serviceGroupMapper.updateById(group);
        return ServiceGroupVO.from(group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ServiceGroup group = requireGroup(id);
        int nextUngroupedOrder = nextUngroupedSortOrder(group.getEnvironmentId());

        List<AppService> groupedServices = serviceMapper.selectList(
                new LambdaQueryWrapper<AppService>()
                        .eq(AppService::getEnvironmentId, group.getEnvironmentId())
                        .eq(AppService::getGroupId, id)
                        .orderByAsc(AppService::getSortOrder)
                        .orderByAsc(AppService::getId)
        );
        for (AppService service : groupedServices) {
            updateServicePosition(service, null, nextUngroupedOrder++);
        }

        serviceGroupMapper.deleteById(id);
        log.info("服务分组已删除, id={}, movedServices={}", id, groupedServices.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateLayout(ServiceLayoutUpdateDTO layoutDTO) {
        if (layoutDTO == null || layoutDTO.getEnvironmentId() == null) {
            throw new BusinessException("环境ID不能为空");
        }
        Long environmentId = layoutDTO.getEnvironmentId();
        requireEnvironment(environmentId);

        List<Long> groupIds = layoutDTO.getGroupIds();
        List<ServiceLayoutUpdateDTO.ServiceBucket> buckets = layoutDTO.getBuckets();
        if (groupIds == null || buckets == null) {
            throw new BusinessException("布局数据不完整");
        }

        List<ServiceGroup> groups = listGroupEntities(environmentId);
        Map<Long, ServiceGroup> groupById = groups.stream()
                .collect(Collectors.toMap(ServiceGroup::getId, group -> group));
        validateGroupIds(groupIds, groupById.keySet());

        Map<Long, List<Long>> serviceIdsByGroup = validateBuckets(buckets, groupById.keySet());
        List<AppService> services = serviceMapper.selectList(
                new LambdaQueryWrapper<AppService>()
                        .eq(AppService::getEnvironmentId, environmentId)
        );
        Map<Long, AppService> serviceById = services.stream()
                .collect(Collectors.toMap(AppService::getId, service -> service));
        validateServiceIds(serviceIdsByGroup, serviceById.keySet());

        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < groupIds.size(); index++) {
            ServiceGroup group = groupById.get(groupIds.get(index));
            if (!Objects.equals(group.getSortOrder(), index)) {
                group.setSortOrder(index);
                group.setUpdatedTime(now);
                serviceGroupMapper.updateById(group);
            }
        }

        for (ServiceLayoutUpdateDTO.ServiceBucket bucket : buckets) {
            Long groupId = bucket.getGroupId();
            List<Long> serviceIds = bucket.getServiceIds();
            for (int index = 0; index < serviceIds.size(); index++) {
                AppService service = serviceById.get(serviceIds.get(index));
                if (!Objects.equals(service.getGroupId(), groupId)
                        || !Objects.equals(service.getSortOrder(), index)) {
                    updateServicePosition(service, groupId, index);
                }
            }
        }
    }

    @Override
    public int nextUngroupedSortOrder(Long environmentId) {
        if (environmentId == null) {
            return 0;
        }
        List<AppService> services = serviceMapper.selectList(
                new LambdaQueryWrapper<AppService>()
                        .eq(AppService::getEnvironmentId, environmentId)
                        .isNull(AppService::getGroupId)
                        .orderByDesc(AppService::getSortOrder)
                        .orderByDesc(AppService::getId)
                        .last("LIMIT 1")
        );
        if (services.isEmpty()) {
            return 0;
        }
        return safeSortOrder(services.get(0).getSortOrder()) + 1;
    }

    private void validateGroupIds(List<Long> providedGroupIds, Set<Long> expectedGroupIds) {
        if (providedGroupIds.contains(null)) {
            throw new BusinessException("命名分组ID不能为空");
        }
        Set<Long> uniqueGroupIds = new LinkedHashSet<>(providedGroupIds);
        if (uniqueGroupIds.size() != providedGroupIds.size()) {
            throw new BusinessException("分组布局中存在重复分组");
        }
        if (!uniqueGroupIds.equals(expectedGroupIds)) {
            throw new BusinessException("分组布局已变化，请刷新后重试");
        }
    }

    private Map<Long, List<Long>> validateBuckets(
            List<ServiceLayoutUpdateDTO.ServiceBucket> buckets,
            Set<Long> expectedGroupIds) {
        Map<Long, List<Long>> serviceIdsByGroup = new HashMap<>();
        boolean hasUngrouped = false;

        for (ServiceLayoutUpdateDTO.ServiceBucket bucket : buckets) {
            if (bucket == null || bucket.getServiceIds() == null) {
                throw new BusinessException("服务布局数据不完整");
            }
            Long groupId = bucket.getGroupId();
            if (groupId == null) {
                if (hasUngrouped) {
                    throw new BusinessException("布局中存在重复的未分组区域");
                }
                hasUngrouped = true;
            } else {
                if (!expectedGroupIds.contains(groupId)) {
                    throw new BusinessException("分组不属于当前环境: " + groupId);
                }
                if (serviceIdsByGroup.containsKey(groupId)) {
                    throw new BusinessException("布局中存在重复分组: " + groupId);
                }
            }
            serviceIdsByGroup.put(groupId, new ArrayList<>(bucket.getServiceIds()));
        }

        if (!hasUngrouped || serviceIdsByGroup.size() != expectedGroupIds.size() + 1) {
            throw new BusinessException("服务布局缺少分组");
        }
        return serviceIdsByGroup;
    }

    private void validateServiceIds(
            Map<Long, List<Long>> serviceIdsByGroup,
            Set<Long> expectedServiceIds) {
        Set<Long> providedServiceIds = new HashSet<>();
        for (List<Long> serviceIds : serviceIdsByGroup.values()) {
            for (Long serviceId : serviceIds) {
                if (serviceId == null || !providedServiceIds.add(serviceId)) {
                    throw new BusinessException("服务布局中存在空值或重复服务");
                }
            }
        }
        if (!providedServiceIds.equals(expectedServiceIds)) {
            throw new BusinessException("服务布局已变化，请刷新后重试");
        }
    }

    private List<ServiceGroup> listGroupEntities(Long environmentId) {
        if (environmentId == null) {
            return Collections.emptyList();
        }
        return serviceGroupMapper.selectList(
                new LambdaQueryWrapper<ServiceGroup>()
                        .eq(ServiceGroup::getEnvironmentId, environmentId)
                        .orderByAsc(ServiceGroup::getSortOrder)
                        .orderByAsc(ServiceGroup::getId)
        );
    }

    private Environment requireEnvironment(Long environmentId) {
        if (environmentId == null) {
            throw new BusinessException("环境ID不能为空");
        }
        Environment environment = environmentMapper.selectById(environmentId);
        if (environment == null) {
            throw new BusinessException("环境不存在: " + environmentId);
        }
        return environment;
    }

    private ServiceGroup requireGroup(Long id) {
        if (id == null) {
            throw new BusinessException("分组ID不能为空");
        }
        ServiceGroup group = serviceGroupMapper.selectById(id);
        if (group == null) {
            throw new BusinessException("服务分组不存在: " + id);
        }
        return group;
    }

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException("分组名称不能为空");
        }
        if (normalized.length() > MAX_GROUP_NAME_LENGTH) {
            throw new BusinessException("分组名称最多50个字符");
        }
        return normalized;
    }

    private void validateNameUnique(Long environmentId, Long excludeId, String name) {
        LambdaQueryWrapper<ServiceGroup> query = new LambdaQueryWrapper<ServiceGroup>()
                .eq(ServiceGroup::getEnvironmentId, environmentId)
                .eq(ServiceGroup::getName, name);
        if (excludeId != null) {
            query.ne(ServiceGroup::getId, excludeId);
        }
        Long count = serviceGroupMapper.selectCount(query);
        if (count != null && count > 0) {
            throw new BusinessException("当前环境已存在同名分组: " + name);
        }
    }

    private int safeSortOrder(Integer sortOrder) {
        return sortOrder == null ? 0 : sortOrder;
    }

    private void updateServicePosition(AppService service, Long groupId, int sortOrder) {
        service.setGroupId(groupId);
        service.setSortOrder(sortOrder);
        serviceMapper.update(
                null,
                new UpdateWrapper<AppService>()
                        .eq("id", service.getId())
                        .set("group_id", groupId)
                        .set("sort_order", sortOrder)
        );
    }
}
