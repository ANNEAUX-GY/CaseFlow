package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统级配置（键值对，2026-11-13）。
 *
 * <p>存全局开关，例如案件阶段变更模式：
 * {@code flow_change_mode} = AUTO 自动变更 / MANUAL 手动确认 / BOTH 两套都要。
 */
@Data
@TableName("system_config")
public class SystemConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 配置键 */
    @TableId
    private String cfgKey;

    /** 配置值 */
    private String cfgValue;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;
}
