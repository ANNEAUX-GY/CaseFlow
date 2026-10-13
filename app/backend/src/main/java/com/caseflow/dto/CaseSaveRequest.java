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

    /**
     * 期限三列是否已被前端显式废弃（2026-10-13）。
     *
     * <p><b>为什么需要这个开关</b>：MyBatis-Plus 的 {@code updateById} 会<b>跳过 null 字段</b>，
     * 所以前端把 {@code deadline=null} 传过来时，库里旧值不会被清掉。
     * 而时间节点表成了唯一真源后，案件表里残留的旧期限会与节点面板自相矛盾
     * （列表显示一个日期、节点面板里另一个）。
     *
     * <p>有了这个标志，服务端就改用 {@code LambdaUpdateWrapper} 显式 set null，
     * 把三列真正清空。默认 false = 老前端不传此字段，行为与从前完全一致。
     */
    private Boolean clearDeadline = false;

    /**
     * 嫌疑人（2026-10-13 建案时一并录入，选填）。
     *
     * <p>只用于<b>新建</b>：编辑案件走详情页的嫌疑人面板逐条增删改
     * （那边有单条快照与撤回，粒度更细）。建案时没有"上一条"可撤，
     * 整批进 after 快照即可。
     */
    private java.util.List<SuspectSaveRequest> suspects;

    /**
     * 重要时间节点（2026-10-13 建案时一并录入，选填）。
     *
     * <p>只用于<b>新建</b>。落库后由TimeNodeService.syncPrimary 把
     * 「最近节点」派生回写 case_info 的期限三列，于是列表/提醒/统计照常工作。
     */
    private java.util.List<TimeNodeSaveRequest> timeNodes;

    /**
     * 待办事项（2026-10-13 建案时一并录入，选填）。
     *
     * <p><b>只用于新建</b>。落库路径与案件详情「添加待办」完全一致：
     * 写成 {@code case_leader_opinion} 再派生 {@code case_todo}，
     * 所以带截止时间、重要程度、常用待办记忆——不是另造一套"手工待办"。
     *
     * <p>空数组 = 不添加待办（建案不带待办是完全合法的，不报错）。
     * 空行（无内容）直接跳过。
     */
    private java.util.List<TodoDraftRequest> todos;
}
