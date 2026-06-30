package com.easy.cd.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 日志查询分页结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogPageVO {

    /** 命中总数（受 OpenSearch track_total_hits 限制） */
    private long total;

    /** 当前页数据 */
    private List<LogItemVO> items;
}
