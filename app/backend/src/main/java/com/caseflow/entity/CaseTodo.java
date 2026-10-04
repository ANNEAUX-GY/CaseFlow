package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 案件待办（to do）：**由领导意见自动派生**——领导在意见面板提一条意见，
 * 本表就有一条对应待办；办案人逐项完成。
 *
 * <p><b>为什么派生而不是各录一套</b>：待办的本质就是"领导要求我做的事"，
 * 人工录两遍必然对不上（意见提了忘建待办、或待办建了没对应意见）。
 * {@code opinionId} 关联 {@link CaseLeaderOpinion}，一条意见对应一条待办。
 *
 * <p>与侦查计划（{@link CasePlan}）的区别：待办强调「完成必须有凭据」——
 * 标记完成前必须先上传至少一份佐证材料（case_file.todo_id 指向本条），
 * 由 {@code TodoService.done} 强制校验。
 */
@Data
@TableName("case_todo")
public class CaseTodo implements Serializable {

    private static final long serialVersionUID = 1L;

    // ---- 紧急程度（手动三档，不随时间变化，排序才稳定）----
    /** 紧急：紧急 */
    public static final String URG_URGENT = "URGENT";
    /** 较急 */
    public static final String URG_HIGH = "HIGH";
    /** 一般（默认档，旧数据 NULL 视为此档） */
    public static final String URG_NORMAL = "NORMAL";

    // ---- 重点程度（手动三档）----
    /** 重点 */
    public static final String IMP_KEY = "KEY";
    /** 次重点 */
    public static final String IMP_MEDIUM = "MEDIUM";
    /** 一般（默认档） */
    public static final String IMP_NORMAL = "NORMAL";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;

    /**
     * 来源领导意见 ID。
     * 派生时写入，是幂等去重的依据——同一条意见重复触发派生不会产生第二条待办。
     * NULL = 历史手工待办。
     */
    private Long opinionId;

    /** 任务标题（取意见内容，冗余存储便于列表展示与检索） */
    private String content;
    /** PENDING 待办 / DONE 已完成 / CANCELLED 已取消 */
    private String status;
    /** 排序，小的在前 */
    private Integer sort;

    /** 紧急程度：URGENT紧急 / HIGH较急 / NORMAL一般；NULL=旧数据按 NORMAL */
    private String urgency;

    /** 重点程度：KEY重点 / MEDIUM次重点 / NORMAL一般；NULL=旧数据按 NORMAL */
    private String importance;

    /** 所属部门/来源（取提意见人所在部门，冗余便于列表展示） */
    private String deptSource;

    /**
     * 截止时间（取意见的落实截止时间）。
     * 与紧急程度<strong>分开</strong>：紧急程度是人的判断，截止时间是客观约束，
     * 两者共同决定"该不该马上做"，但排序时只用紧急程度——否则待办会随时间自己往前挪。
     */
    private LocalDateTime deadline;

    private LocalDateTime doneAt;
    /** 完成人（账号 ID） */
    private Long doneBy;
    /** 完成说明（选填） */
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
