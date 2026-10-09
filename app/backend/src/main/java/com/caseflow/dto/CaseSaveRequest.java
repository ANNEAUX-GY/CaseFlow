package com.caseflow.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 案件新建 / 编辑参数。
 */
@Data
public class CaseSaveRequest {

    private Long id;

    @NotBlank(message = "案件名称不能为空")
    private String name;

    /** MANUAL / PDF / WORD / EXCEL */
    private String sourceType = "MANUAL";

    /** 上传原始材料后返回的文件 ID，用于回填来源类型 */
    private Long sourceFileId;

    /** 案件类别（小类 / 案由），如：殴打他人、诈骗 */
    private String category;

    /** 案卷类型（大类）：PRELIMINARY 初查 / CRIMINAL 刑事 / ADMINISTRATIVE 行政 / CIVIL 民事 */
    private String caseType;

    /** 案件编号（立案登记表编号 / 受案号） */
    private String filingNo;

    /**
     * 强制措施：NONE=未采取 / DETENTION拘留 / ARREST逮捕 / BAIL取保候审 / RESIDENCE监视居住 / SUMMONS拘传。
     *
     * <p>建案表单的「是否采取强制措施」：否 = NONE，是 = 具体措施。
     * <b>留空表示不动这个字段</b>——盯办模块的措施登记才是期限推算的入口，
     * 建案/编辑若无条件回写 null，会把盯办已经登记好的措施抹掉。
     */
    private String caseMeasure;

    private String description;

    /** URGENT / HIGH / NORMAL / LOW */
    private String priority = "NORMAL";

    /**
     * 截止期限。前端按天选择后补上 23:59:59 再传；
     * 后端也兜一层（时间正好是 00:00:00 时按当天 23:59:59 存），见 CaseService#normalizeDeadline。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime deadline;

    /** 期限节点名称（自己填，如：受案时间 / 变更羁押期限时间）；留空=默认叫「截止期限」 */
    private String deadlineLabel;

    /** 该节点提前多少天提醒（NULL / 0 = 不提醒） */
    private Integer remindDays;

    /**
     * 建案/编辑时指定的起始状态，只接受 PENDING_ASSIGN / ASSIGNED。
     * 留空 = 新建案件为「待指派」、编辑案件沿用原状态。
     * 进入「办理中 / 已办结 / 已撤销」必须走 POST /cases/{id}/status（有承办人与权限校验）。
     *
     * 注意：这里**不能给默认值**——前端编辑表单不提交 status，
     * 一旦默认成 PENDING_ASSIGN，编辑任何案件都会把在办案件打回「待指派」。
     */
    private String status;

    private String remark;

    /** 新建时可直接指派：主办人 */
    private Long ownerId;

    /** 新建时可直接指派：协办人 */
    private java.util.List<Long> memberIds;

    private String assignNote;
}
