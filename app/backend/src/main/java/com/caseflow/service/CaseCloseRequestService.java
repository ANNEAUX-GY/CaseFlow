package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseCloseRequest;
import com.caseflow.entity.CaseInfo;
import com.caseflow.exception.BizException;
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
    @Resource private CaseService caseService;
    @Resource private LogService logService;

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
}
