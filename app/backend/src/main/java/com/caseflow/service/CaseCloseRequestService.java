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

/** 经办人发起行政案件结案申请，管理层审核后才改变案件状态。 */
@Service
public class CaseCloseRequestService {
    private static final Set<String> OUTCOMES = new HashSet<>(Arrays.asList(
            "MEDIATION", "PENALTY", "NO_PENALTY", "TERMINATED"));

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

    @Transactional(rollbackFor = Exception.class)
    public synchronized CaseCloseRequest submit(Long caseId, Map<String, String> body) {
        CaseInfo c = requireOpenAdministrative(caseId);
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
        if (!OUTCOMES.contains(outcome)) throw new BizException("请选择合法的行政处理结果");
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

    @Transactional(rollbackFor = Exception.class)
    public synchronized CaseCloseRequest review(Long caseId, Long requestId, boolean approve, String comment) {
        if (!AuthContext.isFullAccess()) throw new BizException(403, "只有管理层可以审核结案申请");
        requireOpenAdministrative(caseId);
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
            com.caseflow.dto.StatusRequest status = new com.caseflow.dto.StatusRequest();
            status.setStatus("DONE");
            caseService.changeStatus(caseId, status);
        }
        logService.log("CASE", "CLOSE_REVIEW", "CASE", caseId,
                approve ? "同意结案申请，案件已办结" : "退回结案申请：" + comment.trim());
        return request;
    }

    private CaseInfo requireCase(Long caseId) {
        CaseInfo c = caseMapper.selectById(caseId);
        if (c == null) throw new BizException("案件不存在");
        return c;
    }

    private CaseInfo requireOpenAdministrative(Long caseId) {
        CaseInfo c = requireCase(caseId);
        if (!"ADMINISTRATIVE".equals(c.getCaseType())) throw new BizException("申请结案仅适用于行政案件");
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
            default: return "终止";
        }
    }
}
