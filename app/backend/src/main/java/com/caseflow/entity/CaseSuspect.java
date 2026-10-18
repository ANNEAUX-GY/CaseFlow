package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 嫌疑人身份信息（随案件快照一并存档，撤回时可整体还原）。
 */
@Data
@TableName("case_suspect")
public class CaseSuspect implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private String name;
    /** MALE 男 / FEMALE 女 */
    private String gender;
    private String idCard;
    private String phone;
    private String address;
    /**
     * 当前强制措施（2026-10-18）：NONE/DETENTION/ARREST/BAIL/RESIDENCE/SUMMONS。
     * 选了具体措施时，下面两个时间必须同时填写。
     */
    private String measure;
    /** 本次措施开始时间 */
    private LocalDateTime measureStartAt;
    /** 下一节点：措施期满 / 结束时间 */
    private LocalDateTime measureEndAt;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
