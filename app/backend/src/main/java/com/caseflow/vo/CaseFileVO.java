package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 附件视图。
 */
@Data
public class CaseFileVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long caseId;
    /** 非空表示这是某个案件待办的佐证材料 */
    private Long todoId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String sizeText;
    private LocalDateTime uploadedAt;
    private String uploadedByName;
}
