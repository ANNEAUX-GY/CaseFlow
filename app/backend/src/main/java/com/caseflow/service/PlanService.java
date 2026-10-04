package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CasePlan;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CasePlanMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.LogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 侦查计划（checklist）：新增 / 编辑 / 勾选完成 / 取消。
 * 全部写操作带前后快照，可撤回。
 *
 * <p>权限模型（2026-10 调整）：计划是<b>办案人自己给自己指定的工作安排</b>，
 * 写操作（新增/编辑/完成/取消）只允许本案现职承办人；管理层（管理员/领导）
 * 只能查看，负责的是审批与提意见，不再代办案人制定或勾销计划。
 */
@Service
public class PlanService {

    @Resource
    private CasePlanMapper planMapper;
    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CaseAssigneeMapper assigneeMapper;
    @Resource
    private CaseSnapshotService snapshotService;
    @Resource
    private LogService logService;

    public List<CasePlan> listOf(Long caseId) {
        return planMapper.selectList(new LambdaQueryWrapper<CasePlan>()
                .eq(CasePlan::getCaseId, caseId)
                .orderByAsc(CasePlan::getSort).orderByAsc(CasePlan::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public CasePlan add(Long caseId, String content, LocalDateTime plannedAt) {
        CaseInfo c = requireCase(caseId);
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写侦查计划内容");
        }
        checkOperate(c);

        String before = snapshotService.capture(caseId);
        CasePlan p = new CasePlan();
        p.setCaseId(caseId);
        p.setContent(content.trim());
        p.setPlannedAt(plannedAt);
        p.setStatus("PENDING");
        p.setSort(nextSort(caseId));
        p.setCreatedBy(AuthContext.userId());
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(p.getCreatedAt());
        planMapper.insert(p);

        touchCase(caseId);
        logService.log("CASE", "PLAN_ADD", "CASE", caseId,
                "新增侦查计划：" + abbrev(p.getContent()), before, snapshotService.capture(caseId));
        return p;
    }

    @Transactional(rollbackFor = Exception.class)
    public CasePlan update(Long planId, String content, LocalDateTime plannedAt) {
        CasePlan p = requirePlan(planId);
        CaseInfo c = requireCase(p.getCaseId());
        checkOperate(c);
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写侦查计划内容");
        }
        String before = snapshotService.capture(p.getCaseId());
        p.setContent(content.trim());
        p.setPlannedAt(plannedAt);
        p.setUpdatedAt(LocalDateTime.now());
        planMapper.updateById(p);

        touchCase(p.getCaseId());
        logService.log("CASE", "PLAN_UPDATE", "CASE", p.getCaseId(),
                "修改侦查计划：" + abbrev(p.getContent()), before, snapshotService.capture(p.getCaseId()));
        return p;
    }

    /** 勾选完成：填完成情况说明 */
    @Transactional(rollbackFor = Exception.class)
    public CasePlan done(Long planId, String doneNote) {
        CasePlan p = requirePlan(planId);
        CaseInfo c = requireCase(p.getCaseId());
        checkOperate(c);
        checkStdOrAssignee(c, p);
        if (!"PENDING".equals(p.getStatus())) {
            throw new BizException("该计划已" + ("DONE".equals(p.getStatus()) ? "完成" : "取消") + "，不能再操作");
        }
        String before = snapshotService.capture(p.getCaseId());
        p.setStatus("DONE");
        p.setDoneAt(LocalDateTime.now());
        p.setDoneNote(doneNote);
        p.setUpdatedAt(p.getDoneAt());
        planMapper.updateById(p);

        touchCase(p.getCaseId());
        logService.log("CASE", "PLAN_DONE", "CASE", p.getCaseId(),
                "完成侦查计划：" + abbrev(p.getContent()), before, snapshotService.capture(p.getCaseId()));
        return p;
    }

    @Transactional(rollbackFor = Exception.class)
    public CasePlan cancel(Long planId) {
        CasePlan p = requirePlan(planId);
        CaseInfo c = requireCase(p.getCaseId());
        checkOperate(c);
        if (!"PENDING".equals(p.getStatus())) {
            throw new BizException("只有待完成的计划可以取消");
        }
        String before = snapshotService.capture(p.getCaseId());
        p.setStatus("CANCELLED");
        p.setUpdatedAt(LocalDateTime.now());
        planMapper.updateById(p);

        touchCase(p.getCaseId());
        logService.log("CASE", "PLAN_CANCEL", "CASE", p.getCaseId(),
                "取消侦查计划：" + abbrev(p.getContent()), before, snapshotService.capture(p.getCaseId()));
        return p;
    }

    /**
     * 撤销完成：把已完成的阶段任务改回待完成。
     *
     * <p>阶段流程面板里的任务勾选框需要<strong>双向切换</strong>——
     * 打错了能改回来。原{@link #done} 只��"完成"单向流转，
     * 且强制要求填完成说明，不适合直接驱动勾选框，故单开一个方法。
     *
     * <p>标准任务（is_std=1）也允许撤销：民警可能有实质工作没做完，
     * 不应因为是模板生成的就锁死。同样留痕、可撤回。
     */
    @Transactional(rollbackFor = Exception.class)
    public CasePlan revert(Long planId) {
        CasePlan p = requirePlan(planId);
        CaseInfo c = requireCase(p.getCaseId());
        checkOperate(c);
        checkStdOrAssignee(c, p);
        if (!"DONE".equals(p.getStatus())) {
            throw new BizException("只有已完成的计划才能撤销完成");
        }
        String before = snapshotService.capture(p.getCaseId());
        p.setStatus("PENDING");
        p.setDoneAt(null);
        p.setDoneNote(null);
        p.setUpdatedAt(LocalDateTime.now());
        planMapper.updateById(p);
        // updateById 会跳过 null 字段，done_at / done_note 必须显式清空，
        // 否则撤销完成后 DB 里残留旧的完成信息
        planMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CasePlan>()
                .eq(CasePlan::getId, planId)
                .set(CasePlan::getDoneAt, null)
                .set(CasePlan::getDoneNote, null));

        touchCase(p.getCaseId());
        logService.log("CASE", "PLAN_REVERT", "CASE", p.getCaseId(),
                "撤销完成：" + abbrev(p.getContent()), before, snapshotService.capture(p.getCaseId()));
        return p;
    }

    // ------------------------------------------------------------------

    private CaseInfo requireCase(Long caseId) {
        if (caseId == null) {
            throw new BizException("缺少案件 ID");
        }
        CaseInfo c = caseMapper.selectById(caseId);
        if (c == null) {
            throw new BizException("案件不存在");
        }
        return c;
    }

    private CasePlan requirePlan(Long planId) {
        if (planId == null) {
            throw new BizException("缺少计划 ID");
        }
        CasePlan p = planMapper.selectById(planId);
        if (p == null) {
            throw new BizException("侦查计划不存在");
        }
        return p;
    }

    /**
     * 写操作权限：<b>自建任务</b>仅本案现职承办人（账号需关联员工图谱）；
     * <b>标准任务</b>（is_std=1，流程模板生成）承办人与管理层都可勾选。
     *
     * <p>区分的理由（2026-10 流程改造）：
     * <ul>
     *   <li>自建任务是<b>民警自己的工作安排</b>，领导不该替办案人"安排工作"或"勾掉工作"
     *       —— 这条边界是盯办模块有意设计的，继续保留；</li>
     *   <li>标准任务是<b>流程必经环节</b>（接收材料/立案/刑拘…），代表全所统一的办理规范。
     *       领导看到某个环节未完成时，需要能直接确认完成，否则进度会永远卡在 99%，
     *       阶段流转的触发条件也就永远不满足。</li>
     * </ul>
     */
    private void checkOperate(CaseInfo c) {
        Long empId = AuthContext.get() == null ? null : AuthContext.get().getEmployeeId();
        if (empId == null) {
            if (AuthContext.isFullAccess()) {
                return;   // 管理层放行，交由调用方按 isStd 细判
            }
            throw new BizException(403, "任务由办案人自行勾选，管理层仅可查看");
        }
        Long cnt = assigneeMapper.selectCount(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getCaseId, c.getId())
                .eq(CaseAssignee::getEmployeeId, empId)
                .eq(CaseAssignee::getStatus, "ACTIVE"));
        if (cnt == null || cnt == 0) {
            if (AuthContext.isFullAccess()) {
                return;
            }
            throw new BizException(403, "任务由办案人自行勾选，管理层仅可查看");
        }
    }

    /**
     * 标准任务（流程模板生成）允许管理层勾选；民警自建任务仍限承办人。
     * 由 {@code checkOperate} 之后调用，拦住「管理层改民警自建任务」这一种情况。
     */
    private void checkStdOrAssignee(CaseInfo c, CasePlan p) {
        if (p.getIsStd() != null && p.getIsStd() == 1) {
            return;   // 标准任务：checkOperate 已放行
        }
        if (AuthContext.isFullAccess()) {
            throw new BizException(403, "该任务由办案人自行制定，管理层仅可查看");
        }
    }

    private void touchCase(Long caseId) {
        CaseInfo c = caseMapper.selectById(caseId);
        if (c != null) {
            c.setUpdatedAt(LocalDateTime.now());
            caseMapper.updateById(c);
        }
    }

    private int nextSort(Long caseId) {
        Long cnt = planMapper.selectCount(new LambdaQueryWrapper<CasePlan>()
                .eq(CasePlan::getCaseId, caseId));
        return (cnt == null ? 0 : cnt.intValue()) + 1;
    }

    private String abbrev(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }
}
