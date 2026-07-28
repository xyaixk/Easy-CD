package com.easy.cd.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 日志游标查询结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogQueryResultVO {

    /** 本批日志（时间倒序，最新在前） */
    private List<LogItemVO> items;

    /** 是否还有更早的日志（本批取满 limit 即认为有） */
    private boolean hasMore;
}
