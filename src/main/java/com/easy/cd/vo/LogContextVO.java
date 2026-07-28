package com.easy.cd.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 日志上下文查询结果：锚点行前后各 N 行（时间正序）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogContextVO {

    /** 时间正序的上下文日志（含锚点行本身） */
    private List<LogItemVO> items;

    /** 锚点行在 items 中的下标，未命中时为 -1 */
    private int anchorIndex;
}
