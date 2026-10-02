package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 待办视图：内容 + 完成状态 + 佐证材料（含上传人 / 上传时间）。
 */
@Data
public class CaseTodoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long caseId;
    /** 冗余案件编号 / 名称：待办总览页要跨案件列出，省得前端再逐条查 */
    private String caseNo;
    private String caseName;
    private String content;
    /** PENDING / DONE */
    private String status;
    private String statusName;
    private Integer sort;
    private LocalDateTime doneAt;
    private String doneByName;
    private String remark;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 佐证材料数量 */
    private Integer evidenceCount;
    /** 佐证材料明细（列表页不填充，仅案件详情 / 总览填充） */
    private List<CaseFileVO> evidence = new ArrayList<>();

    /** 便捷标记：是否已有佐证材料（前端据此提示「需上传佐证」） */
    private Boolean hasEvidence;
}
