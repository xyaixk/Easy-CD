package com.easy.cd.vo;

import lombok.Data;

/**
 * 日志量直方图单桶（按 detected_level 分类计数）。
 */
@Data
public class LogHistogramBucketVO {

    /** 桶起始时间（毫秒时间戳） */
    private long tsMs;

    private long error;

    private long warn;

    private long info;

    private long debug;

    /** 未识别级别 */
    private long other;

    public long total() {
        return error + warn + info + debug + other;
    }
}
