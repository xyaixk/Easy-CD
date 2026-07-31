package com.easy.cd.service.impl;

import com.easy.cd.dto.ServiceGroupCreateDTO;
import com.easy.cd.dto.ServiceLayoutUpdateDTO;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.entity.ServiceGroup;
import com.easy.cd.exception.BusinessException;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ServiceGroupMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.vo.ServiceGroupVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceGroupServiceImplTest {

    @Mock
    private ServiceGroupMapper serviceGroupMapper;

    @Mock
    private ServiceMapper serviceMapper;

    @Mock
    private EnvironmentMapper environmentMapper;

    private ServiceGroupServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ServiceGroupServiceImpl(
                serviceGroupMapper,
                serviceMapper,
                environmentMapper
        );
    }

    @Test
    void createTrimsNameAndAppendsAfterExistingGroups() {
        when(environmentMapper.selectById(1L)).thenReturn(environment(1L));
        when(serviceGroupMapper.selectCount(any())).thenReturn(0L);
        when(serviceGroupMapper.selectList(any())).thenReturn(Arrays.asList(
                group(10L, 1L, "First", 0),
                group(11L, 1L, "Second", 3)
        ));
        doAnswer(invocation -> {
            ServiceGroup created = invocation.getArgument(0);
            created.setId(12L);
            return 1;
        }).when(serviceGroupMapper).insert(any(ServiceGroup.class));

        ServiceGroupCreateDTO dto = new ServiceGroupCreateDTO();
        dto.setEnvironmentId(1L);
        dto.setName("  Production  ");

        ServiceGroupVO created = service.create(dto);

        assertEquals(12L, created.getId());
        assertEquals("Production", created.getName());
        assertEquals(4, created.getSortOrder());
    }

    @Test
    void createRejectsDuplicateNameWithinEnvironment() {
        when(environmentMapper.selectById(1L)).thenReturn(environment(1L));
        when(serviceGroupMapper.selectCount(any())).thenReturn(1L);

        ServiceGroupCreateDTO dto = new ServiceGroupCreateDTO();
        dto.setEnvironmentId(1L);
        dto.setName("Backend");

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.create(dto)
        );

        assertEquals("当前环境已存在同名分组: Backend", error.getMessage());
        verify(serviceGroupMapper, never()).insert(any(ServiceGroup.class));
    }

    @Test
    void deleteMovesGroupedServicesToEndOfUngroupedList() {
        ServiceGroup deletedGroup = group(7L, 1L, "Delete me", 0);
        AppService lastUngrouped = appService(90L, 1L, null, 4);
        AppService firstGrouped = appService(1L, 1L, 7L, 0);
        AppService secondGrouped = appService(2L, 1L, 7L, 1);

        when(serviceGroupMapper.selectById(7L)).thenReturn(deletedGroup);
        when(serviceMapper.selectList(any()))
                .thenReturn(Collections.singletonList(lastUngrouped))
                .thenReturn(Arrays.asList(firstGrouped, secondGrouped));

        service.delete(7L);

        assertNull(firstGrouped.getGroupId());
        assertEquals(5, firstGrouped.getSortOrder());
        assertNull(secondGrouped.getGroupId());
        assertEquals(6, secondGrouped.getSortOrder());
        verify(serviceMapper, times(2)).update(any(), any());
        verify(serviceMapper, never()).updateById(any(AppService.class));
        verify(serviceGroupMapper).deleteById(7L);
    }

    @Test
    void updateLayoutPersistsGroupAndServiceOrder() {
        ServiceGroup firstGroup = group(10L, 1L, "First", 0);
        ServiceGroup secondGroup = group(20L, 1L, "Second", 1);
        AppService firstService = appService(101L, 1L, 10L, 3);
        AppService secondService = appService(102L, 1L, null, 0);
        AppService thirdService = appService(103L, 1L, 20L, 2);

        when(environmentMapper.selectById(1L)).thenReturn(environment(1L));
        when(serviceGroupMapper.selectList(any())).thenReturn(
                Arrays.asList(firstGroup, secondGroup)
        );
        when(serviceMapper.selectList(any())).thenReturn(
                Arrays.asList(firstService, secondService, thirdService)
        );

        service.updateLayout(layout(
                Arrays.asList(20L, 10L),
                bucket(20L, 103L),
                bucket(10L, 102L),
                bucket(null, 101L)
        ));

        assertEquals(1, firstGroup.getSortOrder());
        assertEquals(0, secondGroup.getSortOrder());
        assertNull(firstService.getGroupId());
        assertEquals(0, firstService.getSortOrder());
        assertEquals(10L, secondService.getGroupId());
        assertEquals(0, secondService.getSortOrder());
        assertEquals(20L, thirdService.getGroupId());
        assertEquals(0, thirdService.getSortOrder());
        verify(serviceGroupMapper).updateById(firstGroup);
        verify(serviceGroupMapper).updateById(secondGroup);
        verify(serviceMapper, times(3)).update(any(), any());
        verify(serviceMapper, never()).updateById(any(AppService.class));
    }

    @Test
    void updateLayoutRejectsMissingServiceWithoutPartialWrites() {
        ServiceGroup group = group(10L, 1L, "Only", 0);
        AppService firstService = appService(101L, 1L, 10L, 0);
        AppService missingService = appService(102L, 1L, null, 0);

        when(environmentMapper.selectById(1L)).thenReturn(environment(1L));
        when(serviceGroupMapper.selectList(any())).thenReturn(
                Collections.singletonList(group)
        );
        when(serviceMapper.selectList(any())).thenReturn(
                Arrays.asList(firstService, missingService)
        );

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.updateLayout(layout(
                        Collections.singletonList(10L),
                        bucket(10L, 101L),
                        bucket(null)
                ))
        );

        assertEquals("服务布局已变化，请刷新后重试", error.getMessage());
        verify(serviceGroupMapper, never()).updateById(any(ServiceGroup.class));
        verify(serviceMapper, never()).update(any(), any());
    }

    @Test
    void updateLayoutRejectsForeignGroupBeforeWriting() {
        when(environmentMapper.selectById(1L)).thenReturn(environment(1L));
        when(serviceGroupMapper.selectList(any())).thenReturn(
                Collections.singletonList(group(10L, 1L, "Only", 0))
        );

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.updateLayout(layout(
                        Collections.singletonList(999L),
                        bucket(999L),
                        bucket(null)
                ))
        );

        assertEquals("分组布局已变化，请刷新后重试", error.getMessage());
        verify(serviceMapper, never()).selectList(any());
    }

    private ServiceLayoutUpdateDTO layout(
            List<Long> groupIds,
            ServiceLayoutUpdateDTO.ServiceBucket... buckets) {
        ServiceLayoutUpdateDTO dto = new ServiceLayoutUpdateDTO();
        dto.setEnvironmentId(1L);
        dto.setGroupIds(groupIds);
        dto.setBuckets(Arrays.asList(buckets));
        return dto;
    }

    private ServiceLayoutUpdateDTO.ServiceBucket bucket(Long groupId, Long... serviceIds) {
        ServiceLayoutUpdateDTO.ServiceBucket bucket =
                new ServiceLayoutUpdateDTO.ServiceBucket();
        bucket.setGroupId(groupId);
        bucket.setServiceIds(Arrays.asList(serviceIds));
        return bucket;
    }

    private ServiceGroup group(
            Long id,
            Long environmentId,
            String name,
            Integer sortOrder) {
        ServiceGroup group = new ServiceGroup();
        group.setId(id);
        group.setEnvironmentId(environmentId);
        group.setName(name);
        group.setSortOrder(sortOrder);
        return group;
    }

    private AppService appService(
            Long id,
            Long environmentId,
            Long groupId,
            Integer sortOrder) {
        AppService service = new AppService();
        service.setId(id);
        service.setEnvironmentId(environmentId);
        service.setGroupId(groupId);
        service.setSortOrder(sortOrder);
        return service;
    }

    private Environment environment(Long id) {
        Environment environment = new Environment();
        environment.setId(id);
        environment.setName("env-" + id);
        return environment;
    }
}
