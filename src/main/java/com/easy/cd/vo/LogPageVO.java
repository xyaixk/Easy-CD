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

    /** 命中总数（Loki 无精确 total，这里返回当前查询窗口内的估算分页总数） */
    private long total;

    /** 当前页数据 */
    private List<LogItemVO> items;
}
