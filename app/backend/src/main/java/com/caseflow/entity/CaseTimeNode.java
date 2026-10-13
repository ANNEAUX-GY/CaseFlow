package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 案件重要时间节点（2026-10-13）。
 *
 * <p><b>为什么要独立成表</b>：一份案件的关键时间点不止一个——受案、立案、
 * 刑事拘留期限届满、取保期限届满、补充侦查期限……原先
 * {@code case_info} 上只有一组 {@code deadline_label/deadline/remind_days}，
 * 只能存一个节点，装不下。
 *
 * <p><b>与 case_info 三列的关系</b>：那三列**不删**（列表截止期限、到期提醒、
 * 看板逾期、统计共约 140 处引用），本表才是多节点的真源。每次增删改后由
 * {@code TimeNodeService.syncPrimary} 把「最近节点」派生回写三列，
 * 老逻辑一行不用改。
 *
 * <p>随案件快照一并存档，撤回时可整体还原。
 */
@Data
@TableName("case_time_node")
public class CaseTimeNode implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;

    /**
     * 预设节点类型：ACCEPT 受案 / FILING 立案 / DETAIN_END 刑事拘留期限届满 /
     * BAIL_END 取保期限届满 / RESIDENCE_END 监视居住期限届满 /
     * SUPP_PROBE 补充侦查期限 / ARREST_END 逮捕期限届满 /
     * CUSTODY_CHANGE 变更强制措施 / OTHER 其他。
     *
     * <p>NULL = 自定义名称（用户自己起名，不归入任何预设类型）。
     */
    private String nodeKey;

    /** 节点名称：预设类型会同步写入展示名；自定义时直接取用户填的值 */
    private String nodeName;

    /** 节点日期（按天选，落库统一为该日 23:59:59） */
    private LocalDateTime nodeTime;

    /** 该节点提前多少天提醒（NULL / 0 = 不提醒；各节点独立设置） */
    private Integer remindDays;

    /** 是否主节点：0 否 / 1 是。至多一个，由 syncPrimary 按最近节点自动定 */
    private Integer isPrimary;

    /** 同级排序（录入顺序） */
    private Integer sort;

    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}