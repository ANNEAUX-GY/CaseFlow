package com.caseflow.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 重要时间节点 录入 / 修改参数。
 */
@Data
public class TimeNodeSaveRequest {

    private Long id;

    private Long caseId;

    /**
     * 预设节点类型：ACCEPT 受案 / FILING 立案 / DETAIN_END 刑事拘留期限届满 /
     * BAIL_END 取保期限届满 / RESIDENCE_END 监视居住期限届满 /
     * SUPP_PROBE 补充侦查期限 / ARREST_END 逮捕期限届满 /
     * CUSTODY_CHANGE 变更强制措施 / CUSTODY_TERM_CHANGE 变更羁押期限 / OTHER 其他。
     *
     * <p>留空 = 自定义节点，此时 {@link #nodeName} 必填。
     */
    private String nodeKey;

    /** 节点名称：选了预设类型时可留空（会自动用预设名），自定义时必填 */
    private String nodeName;

    /** 节点日期。前端按天选后补 23:59:59；后端也兜一层（见 TimeNodeService#normalizeTime） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime nodeTime;

    /** 该节点提前多少天提醒（NULL / 0 / 负数 = 不提醒） */
    private Integer remindDays;

    private String remark;
}
