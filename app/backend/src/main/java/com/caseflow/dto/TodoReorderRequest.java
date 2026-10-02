package com.caseflow.dto;

import lombok.Data;

import java.util.List;

/**
 * 待办排序参数：按给定 ID 顺序重排（顺序即 sort 值）。
 */
@Data
public class TodoReorderRequest {

    /** 案件 ID（校验这些待办确实属于本案） */
    private Long caseId;

    /** 待办 ID，按目标顺序排列 */
    private List<Long> ids;
}
