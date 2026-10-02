package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseApproval;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CasePlan;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseApprovalMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CasePlanMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.DictHolder;
import com.caseflow.support.LogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 案件盯办：侦查进度状态机 + 强制措施登记 + 审批留痕。
 *
 * <p>状态机：待初查 --开始侦查--> 侦查中 --提请审批--> 待审批 --同意--> 侦查终结；
 * 待审批 --退回补侦（须填意见）--> 侦查中。
 * 全部流转带前后快照，可撤回；审批/措施/同意/退回 = 管理层专属。
 */
@Service
public class ApprovalService {

    private static final List<String> MEASURES = Arrays.asList("NONE", "DETENTION", "BAIL", "RESIDENCE");
    private static final List<String> ACTIONS = Arrays.asList("START", "SUBMIT", "APPROVE", "REJECT");

    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CasePlanMapper planMapper;
    @Resource
    private CaseApprovalMapper approvalMapper;
    @Resource
    private CaseSnapshotService snapshotService;
    @Resource
    private LogService logService;

    public List<CaseApproval> approvalsOf(Long caseId) {
        return approvalMapper.selectList(new LambdaQueryWrapper<CaseApproval>()
                .eq(CaseApproval::getCaseId, caseId)
                .orderByDesc(CaseApproval::getId));
    }

    /**
     * 侦查进度流转。
     *
     * @param action START 开始侦查 / SUBMIT 提请审批 / APPROVE 同意侦查终结 / REJECT 退回补侦
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseInfo transition(Long caseId, String action, String comment) {
        if (!ACTIONS.contains(action)) {
            throw new BizException("不支持的流转动作");
        }
        CaseInfo c = requireCase(caseId);
        String before = snapshotService.capture(caseId);
        String cur = c.getInvestigationStatus();

        switch (action) {
            case "START":
                // 待初查 -> 侦查中：**仅本案现职承办人（办案人）**。
                // 开始侦查 = 办案人表示"我开始干了"，是工作者的自我认领动作，
                // 不由领导代点（管理层的职责是审批与提意见，见权限划分文档）。
                requireState(cur, null, "PENDING_INITIAL");
                requireActiveHandler(c);
                if (!hasOwner(caseId)) {
                    throw new BizException("请先指派主办人再开始侦查");
                }
                c.setInvestigationStatus("INVESTIGATING");
                break;
            case "SUBMIT":
                // 侦查中 -> 待审批：经办人或管理层；至少 1 条已完成计划
                requireState(cur, null, "INVESTIGATING");
                checkHandlerOrManager(c);
                Long doneCnt = planMapper.selectCount(new LambdaQueryWrapper<CasePlan>()
                        .eq(CasePlan::getCaseId, caseId).eq(CasePlan::getStatus, "DONE"));
                if (doneCnt == null || doneCnt == 0) {
                    throw new BizException("至少完成 1 条侦查计划后才能提请审批");
                }
                c.setInvestigationStatus("PENDING_APPROVAL");
                break;
            case "APPROVE":
                // 待审批 -> 侦查终结：管理层
                requireState(cur, null, "PENDING_APPROVAL");
                requireManager("审批侦查终结");
                c.setInvestigationStatus("INVESTIGATION_DONE");
                insertApproval(caseId, "INVESTIGATION_DONE", "APPROVED", comment);
                break;
            case "REJECT":
                // 待审批 -> 侦查中（退回补侦）：管理层，意见必填
                requireState(cur, null, "PENDING_APPROVAL");
                requireManager("退回补侦");
                if (!StringUtils.hasText(comment)) {
                    throw new BizException("退回补侦必须填写审批意见");
                }
                c.setInvestigationStatus("INVESTIGATING");
                insertApproval(caseId, "INVESTIGATION_DONE", "RETURNED", comment);
                break;
            default:
                throw new BizException("不支持的流转动作");
        }

        c.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(c);
        logService.log("CASE", "INVESTIGATION", "CASE", caseId,
                "侦查进度：" + nameOf(cur) + " → " + nameOf(c.getInvestigationStatus())
                        + (StringUtils.hasText(comment) ? "（意见：" + abbrev(comment) + "）" : ""),
                before, snapshotService.capture(caseId));
        return c;
    }

    /**
     * 强制措施登记/变更（管理层专属）：自动按措施类型推算期限届满日（可显式覆盖）。
     * NONE 表示无措施（回到初查视图）。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseInfo measure(Long caseId, String measure, LocalDateTime measureDate,
                            LocalDateTime detainDeadline, String comment) {
        if (!MEASURES.contains(measure)) {
            throw new BizException("强制措施类型不合法");
        }
        CaseInfo c = requireCase(caseId);
        requireManager("登记强制措施");

        String before = snapshotService.capture(caseId);
        LocalDateTime base = measureDate != null ? measureDate : LocalDateTime.now();

        c.setCaseMeasure("NONE".equals(measure) ? null : measure);
        c.setMeasureDate("NONE".equals(measure) ? null : base);
        if ("NONE".equals(measure)) {
            c.setDetainDeadline(null);
        } else if (detainDeadline != null) {
            c.setDetainDeadline(detainDeadline);
        } else {
            c.setDetainDeadline(defaultDeadline(measure, base));
        }
        c.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(c);

        insertApproval(caseId, "MEASURE", "APPROVED",
                "登记强制措施：" + DictHolder.name("CASE_MEASURE", measure)
                        + (StringUtils.hasText(comment) ? "；" + comment : ""));

        logService.log("CASE", "MEASURE", "CASE", caseId,
                "强制措施：" + (DictHolder.name("CASE_MEASURE", c.getCaseMeasure()) == null ? "无"
                        : DictHolder.name("CASE_MEASURE", c.getCaseMeasure()))
                        + (c.getDetainDeadline() != null ? "，期限至 " + c.getDetainDeadline() : ""),
                before, snapshotService.capture(caseId));
        return c;
    }

    /** 措施期限推算默认值：刑拘 +30 天（提请批捕期限），取保 +12 个月，监居 +6 个月 */
    private LocalDateTime defaultDeadline(String measure, LocalDateTime base) {
        switch (measure) {
            case "DETENTION":
                return base.plusDays(30);
            case "BAIL":
                return base.plusMonths(12);
            case "RESIDENCE":
                return base.plusMonths(6);
            default:
                return null;
        }
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

    private void requireState(String current, String ignored, String expected) {
        boolean ok = expected == null
                ? current == null
                : expected.equals(current) || (current == null && "PENDING_INITIAL".equals(expected));
        if (!ok) {
            throw new BizException("当前侦查进度为「" + nameOf(current) + "」，不能执行该操作");
        }
    }

    private void requireManager(String what) {
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "当前角色无权执行：" + what + "，需所长/副所长/法制员/管理员");
        }
    }

    /** 经办人（本案现职承办人）或管理层 */
    private void checkHandlerOrManager(CaseInfo c) {
        if (AuthContext.isFullAccess()) {
            return;
        }
        requireActiveHandler(c);
    }

    /** 仅本案现职承办人（办案人）——开始侦查、提请审批等工作动作专用 */
    private void requireActiveHandler(CaseInfo c) {
        Long empId = AuthContext.get() == null ? null : AuthContext.get().getEmployeeId();
        if (empId == null) {
            throw new BizException(403, "该操作由办案人本人执行，且账号需关联员工档案");
        }
        Long cnt = assigneeMapper.selectCount(new LambdaQueryWrapper<com.caseflow.entity.CaseAssignee>()
                .eq(com.caseflow.entity.CaseAssignee::getCaseId, c.getId())
                .eq(com.caseflow.entity.CaseAssignee::getEmployeeId, empId)
                .eq(com.caseflow.entity.CaseAssignee::getStatus, "ACTIVE"));
        if (cnt == null || cnt == 0) {
            throw new BizException(403, "该操作由办案人本人执行（管理层不代点：开始侦查表示办案人开始工作）");
        }
    }

    @Resource
    private com.caseflow.mapper.CaseAssigneeMapper assigneeMapper;

    private boolean hasOwner(Long caseId) {
        Long cnt = assigneeMapper.selectCount(new LambdaQueryWrapper<com.caseflow.entity.CaseAssignee>()
                .eq(com.caseflow.entity.CaseAssignee::getCaseId, caseId)
                .eq(com.caseflow.entity.CaseAssignee::getAssignRole, "OWNER")
                .eq(com.caseflow.entity.CaseAssignee::getStatus, "ACTIVE"));
        return cnt != null && cnt > 0;
    }

    private void insertApproval(Long caseId, String type, String result, String comment) {
        CaseApproval a = new CaseApproval();
        a.setCaseId(caseId);
        a.setApproveType(type);
        a.setResult(result);
        a.setComment(comment);
        a.setApproverId(AuthContext.userId());
        a.setApproverName(AuthContext.userName());
        a.setCreatedAt(LocalDateTime.now());
        approvalMapper.insert(a);
    }

    private String nameOf(String status) {
        String n = DictHolder.name("INVESTIGATION_STATUS", status);
        return n == null ? "待初查" : n;
    }

    private String abbrev(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }
}
