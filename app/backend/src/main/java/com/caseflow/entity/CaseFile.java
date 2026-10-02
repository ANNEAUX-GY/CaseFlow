package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 案件附件（原始材料 / 过程材料）。
 */
@Data
@TableName("case_file")
public class CaseFile implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    /** 非空表示这条附件是某个案件待办（case_todo）的佐证材料 */
    private Long todoId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String storagePath;
    private Long uploadedBy;
    private LocalDateTime uploadedAt;
}
