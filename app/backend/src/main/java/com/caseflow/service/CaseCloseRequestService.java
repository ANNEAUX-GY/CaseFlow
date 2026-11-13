package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseApproval;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseCloseRequest;
import com.caseflow.entity.CaseInfo;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseApprovalMapper;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseCloseRequestMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.LogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 经办人发起结案申请，案管/领导审核后才改变案件状态；也可由案管/领导直接结案。 */
@Service
public class CaseCloseRequestService {
    // 行政结案结果
    private static final Set<String> ADMIN_OUTCOMES = new HashSet<>(Arrays.asList(
            "MEDIATION", "PENALTY", "NO_PENALTY", "TERMINATED"));
    // 刑事结案结果（2026-10-30）：撤案 / 移送预审 / 移送其他单位
    private static final Set<String> CRIMINAL_OUTCOMES = new HashSet<>(Arrays.asList(
            "WITHDRAW", "TRANSFER_PRETRIAL", "TRANSFER_OTHER"));

    @Resource private CaseCloseRequestMapper requestMapper;
    @Resource private CaseInfoMapper caseMapper;
    @Resource private CaseAssigneeMapper assigneeMapper;
    @Resource private CaseApprovalMapper approvalMapper;
    @Resource private CaseService caseService;
    @Resource private LogService logService;
    @Resource private SystemConfigService configService;

    public CaseCloseRequest latest(Long caseId) {
        requireCase(caseId);
        requireVisible(caseId);
        List<CaseCloseRequest> rows = requestMapper.selectList(new LambdaQueryWrapper<CaseCloseRequest>()
                .eq(CaseCloseRequest::getCaseId, caseId).orderByDesc(CaseCloseRequest::getId).last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 结案申请（经办人提交，2026-10-30 起支持刑事 + 行政）。
     *
     * <p>经办人只能申请，不直接改状态；案管/领导审批通过后落地。
     * 结案结果按案件类型区分：
     * <ul>
     *   <li>行政：处罚 / 不予处罚 / 终止调查 / 调解；</li>
     *   <li>刑事：撤案 / 移送预审 / 移送其他单位。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public synchronized CaseCloseRequest submit(Long caseId, Map<String, String> body) {
        CaseInfo c = requireOpenCase(caseId);
        requireHandler(caseId);
        if (body == null) throw new BizException("请填写结案申请内容");
        CaseCloseRequest previous = latest(caseId);
        if (previous != null && "PENDING".equals(previous.getStatus())) {
            throw new BizException("结案申请正在等待审核，请勿重复提交");
        }
        String outcome = trim(body.get("outcome"));
        String note = trim(body.get("note"));
        String platform = trim(body.get("uploadPlatform"));
        String action = trim(body.get("uploadFile"));
        String operationTime = trim(body.get("uploadTime"));
        if (!validOutcome(c.getCaseType(), outcome)) throw new BizException("请选择合法的结案结果");
        if (platform.isEmpty() != action.isEmpty()) throw new BizException("执行平台和操作名称须同时填写");
        if (note.isEmpty() && platform.isEmpty()) throw new BizException("请填写处理说明或执行记录");
        if (note.length() > 600 || platform.length() > 100 || action.length() > 200
                || operationTime.length() > 32)
            throw new BizException("结案反馈内容超出长度限制");
        CaseCloseRequest request = new CaseCloseRequest();
        request.setCaseId(c.getId());
        request.setOutcome(outcome);
        request.setNote(note);
        request.setOperationTime(platform.isEmpty() ? null : operationTime);
        request.setOperationPlatform(platform.isEmpty() ? null : platform);
        request.setOperationAction(platform.isEmpty() ? null : action);
        request.setStatus("PENDING");
        request.setSubmittedBy(AuthContext.userId());
        request.setSubmittedByName(AuthContext.userName());
        request.setSubmittedAt(LocalDateTime.now());
        requestMapper.insert(request);
        logService.log("CASE", "CLOSE_REQUEST", "CASE", caseId,
                "申请结案：" + outcomeName(outcome) + "；" + note + " "
                        + FeedbackDeclare.sentence(request.getOperationTime(), platform, action));
        return request;
    }

    /**
     * 案管/领导直接结案（2026-10-30）：不走审批，立即生效。
     *
     * @param outcome 结案结果（刑事/行政各自合法取值）
     * @param note 处理说明
     */
    @Transactional(rollbackFor = Exception.class)
    public synchronized CaseCloseRequest directClose(Long caseId, Map<String, String> body) {
        if (!AuthContext.isFullAccess()) throw new BizException(403, "只有案管或领导可以直接结案");
        CaseInfo c = requireOpenCase(caseId);
        if (body == null) throw new BizException("请填写结案内容");
        String outcome = trim(body.get("outcome"));
        String note = trim(body.get("note"));
        if (!validOutcome(c.getCaseType(), outcome)) throw new BizException("请选择合法的结案结果");
        if (note.length() > 600) throw new BizException("处理说明超出长度限制");
        CaseCloseRequest request = new CaseCloseRequest();
        request.setCaseId(c.getId());
        request.setOutcome(outcome);
        request.setNote(note);
        request.setStatus("APPROVED");
        request.setSubmittedBy(AuthContext.userId());
        request.setSubmittedByName(AuthContext.userName());
        request.setSubmittedAt(LocalDateTime.now());
        request.setReviewedBy(AuthContext.userId());
        request.setReviewedByName(AuthContext.userName());
        request.setReviewedAt(LocalDateTime.now());
        request.setReviewComment("案管/领导直接结案");
        requestMapper.insert(request);
        // 直接落地：撤案→已撤销，其余→已办结
        applyOutcome(caseId, outcome);
        logService.log("CASE", "CLOSE_DIRECT", "CASE", caseId,
                "直接结案：" + outcomeName(outcome) + "；" + note);
        return request;
    }

    @Transactional(rollbackFor = Exception.class)
    public synchronized CaseCloseRequest review(Long caseId, Long requestId, boolean approve, String comment) {
        if (!AuthContext.isFullAccess()) throw new BizException(403, "只有案管或领导可以审核结案申请");
        requireOpenCase(caseId);
        CaseCloseRequest request = requestMapper.selectById(requestId);
        if (request == null || !caseId.equals(request.getCaseId()) || !"PENDING".equals(request.getStatus()))
            throw new BizException("结案申请不存在或已审核");
        if (!approve && !StringUtils.hasText(comment)) throw new BizException("退回申请须填写原因");
        if (comment != null && comment.length() > 500) throw new BizException("审核意见不能超过500字");
        request.setStatus(approve ? "APPROVED" : "RETURNED");
        request.setReviewedBy(AuthContext.userId());
        request.setReviewedByName(AuthContext.userName());
        request.setReviewedAt(LocalDateTime.now());
        request.setReviewComment(trim(comment));
        requestMapper.updateById(request);
        if (approve) {
            applyOutcome(caseId, request.getOutcome());
        }
        logService.log("CASE", "CLOSE_REVIEW", "CASE", caseId,
                approve ? "同意结案申请，案件已办结" : "退回结案申请：" + comment.trim());
        return request;
    }

    /** 结案结果落地到案件状态：撤案→已撤销(CANCELLED)，其余→已办结(DONE) */
    private void applyOutcome(Long caseId, String outcome) {
        com.caseflow.dto.StatusRequest status = new com.caseflow.dto.StatusRequest();
        status.setStatus("WITHDRAW".equals(outcome) ? "CANCELLED" : "DONE");
        caseService.changeStatus(caseId, status);
    }

    // ------------------------------------------------------------------
    // 结案结果的自动/手动登记（2026-11-13）
    // ------------------------------------------------------------------
    //
    // 与「案件阶段变更模式」同构：全局开关 close_mode 决定登记结案结果后
    // 自动结案还是生成待确认结案；自动结案后盯办提醒管理用户与承办人，
    // 承办人可提异议退回，领导可直接撤回或审批异议。
    //
    // 复用 case_approval 表（approveType=CLOSE）承载结案留痕：
    //   flow_action  → 结案结果编码（WITHDRAW/TRANSFER_PRETRIAL/...）
    //   from_stage   → 结案前的案件状态（撤回/退回时据此回退）
    //   result       → AUTO自动结案 / PENDING待确认 / DISPUTE异议 /
    //                  RETURNED同意退回 / REJECTED驳回异议 / WITHDRAWN撤回
    // 与既有「一键结案」（directClose 立即生效、submit+review 申请审批）并存互不干扰。

    /**
     * 登记结案结果（自动/手动由全局开关分派）。
     *
     * <p>权限：管理层（isFullAccess）或本案现职承办人。
     * 结果按案件类型校验：刑事=撤案/移送预审/移送其他单位；行政=处罚/不予处罚/终止调查/调解。
     *
     * <ul>
     *   <li>{@code AUTO}  → 直接结案落地，记一条 result=AUTO 的留痕，可异议/撤回；</li>
     *   <li>{@code MANUAL}→ 不落地，生成 result=PENDING 的待确认结案，领导审批后落地；</li>
     *   <li>{@code BOTH}  → 自动结案 + 保留手动确认入口（与 AUTO 同构，手动申请入口仍在）。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public synchronized CaseApproval registerOutcome(Long caseId, Map<String, String> body) {
        CaseInfo c = requireOpenCase(caseId);
        if (!AuthContext.isFullAccess()) {
            requireHandler(caseId);
        }
        if (body == null) throw new BizException("请填写结案结果");
        String outcome = trim(body.get("outcome"));
        String note = trim(body.get("note"));
        if (!validOutcome(c.getCaseType(), outcome)) throw new BizException("请选择合法的结案结果");
        if (note.length() > 600) throw new BizException("处理说明超出长度限制");

        String fromStatus = c.getStatus() == null ? "IN_PROGRESS" : c.getStatus();

        // 自动结案（AUTO / BOTH）：落地 + 留 AUTO 留痕
        if (configService.autoCloseEnabled()) {
            CaseApproval a = recordAutoClose(c.getId(), outcome, fromStatus, note);
            applyOutcome(caseId, outcome);
            logService.log("CASE", "CLOSE_AUTO", "CASE", caseId,
                    "登记结案结果并自动结案：" + outcomeName(outcome) + "；" + note);
            return a;
        }

        // 手动确认（MANUAL）：生成待确认结案，领导审批后落地
        CaseApproval a = recordManualClose(c.getId(), outcome, fromStatus, note);
        logService.log("CASE", "CLOSE_PENDING", "CASE", caseId,
                "登记结案结果（待管理用户确认结案）：" + outcomeName(outcome) + "；" + note);
        return a;
    }

    /** 自动结案留痕：result=AUTO，供承办人异议 / 领导撤回 */
    private CaseApproval recordAutoClose(Long caseId, String outcome, String fromStatus, String note) {
        CaseApproval a = new CaseApproval();
        a.setCaseId(caseId);
        a.setApproveType("CLOSE");
        a.setResult("AUTO");
        a.setFlowAction(outcome);
        a.setFromStage(fromStatus);
        a.setComment(note);
        a.setApproverId(AuthContext.userId());
        a.setApproverName(AuthContext.userName());
        a.setCreatedAt(LocalDateTime.now());
        approvalMapper.insert(a);
        return a;
    }

    /** 手动结案留痕：result=PENDING 待确认，领导审批同意后落地 */
    private CaseApproval recordManualClose(Long caseId, String outcome, String fromStatus, String note) {
        // 已有待确认的结案申请则不再重复生成
        List<CaseApproval> pending = approvalMapper.selectList(new LambdaQueryWrapper<CaseApproval>()
                .eq(CaseApproval::getCaseId, caseId)
                .eq(CaseApproval::getApproveType, "CLOSE")
                .eq(CaseApproval::getResult, "PENDING"));
        if (!pending.isEmpty()) {
            return pending.get(0);
        }
        CaseApproval a = new CaseApproval();
        a.setCaseId(caseId);
        a.setApproveType("CLOSE");
        a.setResult("PENDING");
        a.setFlowAction(outcome);
        a.setFromStage(fromStatus);
        a.setComment(note);
        a.setApplicantId(AuthContext.userId());
        a.setApplicantName(AuthContext.userName());
        a.setAppliedAt(LocalDateTime.now());
        a.setCreatedAt(a.getAppliedAt());
        approvalMapper.insert(a);
        return a;
    }

    /**
     * 领导审批「待确认结案」（手动模式落地）。
     *
     * <p>同意 → 落地结案（changeStatus）；退回 → 不结案，留意见。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reviewManualClose(Long approvalId, boolean approve, String comment) {
        if (!AuthContext.isFullAccess()) throw new BizException(403, "确认结案需管理层权限");
        CaseApproval a = approvalMapper.selectById(approvalId);
        if (a == null || !"CLOSE".equals(a.getApproveType()) || !"PENDING".equals(a.getResult())) {
            throw new BizException("该记录不是待确认的结案申请");
        }
        if (!approve && !StringUtils.hasText(comment)) throw new BizException("退回须填写原因");
        CaseInfo c = requireOpenCase(a.getCaseId());
        a.setResult(approve ? "APPROVED" : "RETURNED");
        a.setApproverId(AuthContext.userId());
        a.setApproverName(AuthContext.userName());
        a.setComment(StringUtils.hasText(comment) ? comment.trim() : "同意结案");
        approvalMapper.updateById(a);
        if (approve) {
            applyOutcome(a.getCaseId(), a.getFlowAction());
        }
        logService.log("CASE", "CLOSE_REVIEW", "CASE", a.getCaseId(),
                approve ? "确认结案，案件已办结" : "退回结案确认：" + abbrev(comment));
    }

    /**
     * 承办人对「自动结案」提异议。
     *
     * <p>针对 result=AUTO 的 CLOSE 留痕：承办人认为不该结案（如移送有误），
     * 提一条异议置为 DISPUTE，等领导审批。异议必填理由，且只能由本案现职承办人提出。
     */
    @Transactional(rollbackFor = Exception.class)
    public void disputeAutoClose(Long approvalId, String comment) {
        CaseApproval a = approvalMapper.selectById(approvalId);
        if (a == null || !"CLOSE".equals(a.getApproveType()) || !"AUTO".equals(a.getResult())) {
            throw new BizException("该记录不是可异议的自动结案");
        }
        if (!StringUtils.hasText(comment)) throw new BizException("异议必须填写理由");
        CaseInfo c = requireCase(a.getCaseId());
        if (!AuthContext.isFullAccess()) {
            requireHandler(a.getCaseId());
        }
        a.setResult("DISPUTE");
        a.setComment("【异议】" + comment.trim());
        a.setApplicantId(AuthContext.userId());
        a.setApplicantName(AuthContext.userName());
        a.setAppliedAt(LocalDateTime.now());
        approvalMapper.updateById(a);
        logService.log("CASE", "CLOSE_DISPUTE", "CASE", a.getCaseId(),
                "对自动结案提异议（" + outcomeName(a.getFlowAction()) + "）：" + abbrev(comment));
    }

    /**
     * 领导审批「自动结案异议」（同意退回 / 不同意退回）。
     *
     * <p>同意退回：把案件状态回退到结案前的 from_stage（重新打开案件）；
     * 不同意：维持结案。两种都留审批意见。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reviewCloseDispute(Long approvalId, boolean approve, String comment) {
        if (!AuthContext.isFullAccess()) throw new BizException(403, "审批结案异议需管理层权限");
        CaseApproval a = approvalMapper.selectById(approvalId);
        if (a == null || !"CLOSE".equals(a.getApproveType()) || !"DISPUTE".equals(a.getResult())) {
            throw new BizException("该记录不是待审批的结案异议");
        }
        if (approve && !StringUtils.hasText(comment)) throw new BizException("同意退回须填写退回意见");
        CaseInfo c = requireCase(a.getCaseId());
        a.setResult(approve ? "RETURNED" : "REJECTED");
        a.setApproverId(AuthContext.userId());
        a.setApproverName(AuthContext.userName());
        a.setComment(approve ? ("同意退回：" + comment.trim())
                : ("不同意退回" + (StringUtils.hasText(comment) ? "：" + comment.trim() : "")));
        approvalMapper.updateById(a);
        if (approve) {
            reopenCase(c, a.getFromStage());
        }
        logService.log("CASE", "CLOSE_DISPUTE_REVIEW", "CASE", a.getCaseId(),
                (approve ? "同意退回结案异议" : "不同意退回结案异议")
                        + "（" + outcomeName(a.getFlowAction()) + "）");
    }

    /**
     * 领导直接撤回「自动结案」，把案件状态回退到结案前。
     *
     * <p>不需要承办人异议，领导不认可这次自动结案即可直接撤回。
     */
    @Transactional(rollbackFor = Exception.class)
    public void withdrawAutoClose(Long approvalId, String comment) {
        if (!AuthContext.isFullAccess()) throw new BizException(403, "撤回自动结案需管理层权限");
        CaseApproval a = approvalMapper.selectById(approvalId);
        if (a == null || !"CLOSE".equals(a.getApproveType())
                || !("AUTO".equals(a.getResult()) || "DISPUTE".equals(a.getResult()))) {
            throw new BizException("该记录不是可撤回的自动结案");
        }
        CaseInfo c = requireCase(a.getCaseId());
        reopenCase(c, a.getFromStage());
        a.setResult("WITHDRAWN");
        a.setApproverId(AuthContext.userId());
        a.setApproverName(AuthContext.userName());
        a.setComment("撤回自动结案" + (StringUtils.hasText(comment) ? "：" + comment.trim() : ""));
        approvalMapper.updateById(a);
        logService.log("CASE", "CLOSE_WITHDRAW", "CASE", a.getCaseId(),
                "撤回自动结案（" + outcomeName(a.getFlowAction()) + "）");
    }

    /** 把案件从结案态重新打开，回退到结案前的状态（回退时沿用变更状态链路留痕） */
    private void reopenCase(CaseInfo c, String fromStatus) {
        String back = (fromStatus == null || "DONE".equals(fromStatus) || "CANCELLED".equals(fromStatus))
                ? "IN_PROGRESS" : fromStatus;
        com.caseflow.dto.StatusRequest status = new com.caseflow.dto.StatusRequest();
        status.setStatus(back);
        status.setRemark("撤回/退回自动结案，恢复办案");
        caseService.changeStatus(c.getId(), status);
    }

    /** 本案最近一条结案登记留痕（含 PENDING 待确认 / AUTO / DISPUTE），前端展示用 */
    public CaseApproval latestCloseTraceOf(Long caseId) {
        List<CaseApproval> list = approvalMapper.selectList(new LambdaQueryWrapper<CaseApproval>()
                .eq(CaseApproval::getCaseId, caseId)
                .eq(CaseApproval::getApproveType, "CLOSE")
                .in(CaseApproval::getResult, "PENDING", "AUTO", "DISPUTE")
                .orderByDesc(CaseApproval::getId));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 全局限的待确认结案申请（工作台用）；caseId 不为空时只看该案件 */
    public List<CaseApproval> pendingCloseApprovals(Long caseId) {
        LambdaQueryWrapper<CaseApproval> q = new LambdaQueryWrapper<CaseApproval>()
                .eq(CaseApproval::getApproveType, "CLOSE")
                .eq(CaseApproval::getResult, "PENDING")
                .orderByAsc(CaseApproval::getAppliedAt);
        if (caseId != null) q.eq(CaseApproval::getCaseId, caseId);
        return approvalMapper.selectList(q);
    }

    /** 全局限的待审批自动结案异议（工作台用）；caseId 不为空时只看该案件 */
    public List<CaseApproval> pendingCloseDisputes(Long caseId) {
        LambdaQueryWrapper<CaseApproval> q = new LambdaQueryWrapper<CaseApproval>()
                .eq(CaseApproval::getApproveType, "CLOSE")
                .eq(CaseApproval::getResult, "DISPUTE")
                .orderByAsc(CaseApproval::getAppliedAt);
        if (caseId != null) q.eq(CaseApproval::getCaseId, caseId);
        return approvalMapper.selectList(q);
    }

    private String abbrev(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() <= 60 ? t : t.substring(0, 60) + "…";
    }


    /** 结案结果是否对该案件类型合法 */
    private boolean validOutcome(String caseType, String outcome) {
        if (!StringUtils.hasText(outcome)) return false;
        if ("CRIMINAL".equals(caseType)) return CRIMINAL_OUTCOMES.contains(outcome);
        return ADMIN_OUTCOMES.contains(outcome);
    }

    private CaseInfo requireCase(Long caseId) {
        CaseInfo c = caseMapper.selectById(caseId);
        if (c == null) throw new BizException("案件不存在");
        return c;
    }

    /** 结案只适用于尚未办结/撤销的案件；刑事、行政均可 */
    private CaseInfo requireOpenCase(Long caseId) {
        CaseInfo c = requireCase(caseId);
        if ("DONE".equals(c.getStatus()) || "CANCELLED".equals(c.getStatus()))
            throw new BizException("案件已经办结或撤销");
        return c;
    }

    private void requireVisible(Long caseId) {
        if (!AuthContext.isFullAccess()) requireHandler(caseId);
    }

    private void requireHandler(Long caseId) {
        Long employeeId = AuthContext.get() == null ? null : AuthContext.get().getEmployeeId();
        if (employeeId == null || assigneeMapper.selectCount(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getCaseId, caseId).eq(CaseAssignee::getEmployeeId, employeeId)
                .eq(CaseAssignee::getStatus, "ACTIVE")) == 0) {
            throw new BizException(403, "只有本案现职经办人可以申请结案");
        }
    }

    private String trim(String value) { return value == null ? "" : value.trim(); }
    private String outcomeName(String outcome) {
        switch (outcome) {
            case "MEDIATION": return "调解";
            case "PENALTY": return "处罚";
            case "NO_PENALTY": return "不予处罚";
            case "TERMINATED": return "终止调查";
            case "WITHDRAW": return "撤案";
            case "TRANSFER_PRETRIAL": return "移送预审";
            case "TRANSFER_OTHER": return "移送其他单位";
            default: return outcome;
        }
    }

    /** 结案结果中文名（对外，前端/Controller 展示用） */
    public String outcomeLabel(String outcome) {
        return outcomeName(outcome);
    }
}
