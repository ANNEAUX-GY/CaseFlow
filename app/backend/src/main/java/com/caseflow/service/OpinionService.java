package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseLeaderOpinion;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CaseLeaderOpinionMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.LogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 领导意见与落实反馈。
 *
 * <p>权限模型：
 * <ul>
 *   <li>提出意见 = <b>管理层</b>（管理员 + 领导；需求主语是"领导"，管理员同属管理端）；</li>
 *   <li>反馈落实 = <b>本案现职承办人（办案人）</b>：对每条意见标记 完成/进行中/未完成，
 *       并记录反馈说明；反馈时间由服务端生成，防止前端伪造时间线；</li>
 *   <li>查看 = 所有能查看该案件详情的人。</li>
 * </ul>
 *
 * <p>每条意见只保留最新一次反馈（覆盖式）；反馈历史经 operation_log 时间线可查。
 */
@Service
public class OpinionService {

    private static final List<String> FEEDBACK_STATUSES =
            Arrays.asList(CaseLeaderOpinion.FB_DONE, CaseLeaderOpinion.FB_IN_PROGRESS, CaseLeaderOpinion.FB_NOT_DONE);

    @Resource
    private CaseLeaderOpinionMapper opinionMapper;
    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CaseAssigneeMapper assigneeMapper;
    @Resource
    private LogService logService;

    public List<CaseLeaderOpinion> listOf(Long caseId) {
        return opinionMapper.selectList(new LambdaQueryWrapper<CaseLeaderOpinion>()
                .eq(CaseLeaderOpinion::getCaseId, caseId)
                .orderByDesc(CaseLeaderOpinion::getId));
    }

    /** 提出意见（管理层）。一个案件可提多条。 */
    @Transactional(rollbackFor = Exception.class)
    public CaseLeaderOpinion add(Long caseId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写意见内容");
        }
        requireCase(caseId);
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "只有管理员或领导可以提出意见");
        }

        CaseLeaderOpinion o = new CaseLeaderOpinion();
        o.setCaseId(caseId);
        o.setContent(content.trim());
        o.setCreatorId(AuthContext.userId());
        o.setCreatorName(AuthContext.userName());
        o.setCreatedAt(LocalDateTime.now());
        opinionMapper.insert(o);

        logService.log("CASE", "OPINION_ADD", "CASE", caseId,
                "提出意见：" + abbrev(o.getContent()));
        return o;
    }

    /**
     * 办案人反馈落实情况：对某条意见标记 完成/进行中/未完成，可附说明。
     * 说明里通常带结构化"上传声明"句（于【时间】在【平台】上传了【文件】），
     * 该句由前端在回复弹窗里组装——本服务原样保存，不再上传任何文件。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseLeaderOpinion feedback(Long opinionId, String status, String note) {
        if (!FEEDBACK_STATUSES.contains(status)) {
            throw new BizException("落实状态必须是：完成 / 进行中 / 未完成");
        }
        CaseLeaderOpinion o = requireOpinion(opinionId);
        requireCase(o.getCaseId());

        // 反馈人 = 本案现职承办人（办案人），管理层代填反而会掩盖"谁在落实"
        Long empId = AuthContext.get() == null ? null : AuthContext.get().getEmployeeId();
        if (empId == null) {
            throw new BizException(403, "只有本案承办人可以反馈意见落实情况");
        }
        Long cnt = assigneeMapper.selectCount(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getCaseId, o.getCaseId())
                .eq(CaseAssignee::getEmployeeId, empId)
                .eq(CaseAssignee::getStatus, "ACTIVE"));
        if (cnt == null || cnt == 0) {
            throw new BizException(403, "只有本案承办人可以反馈意见落实情况");
        }

        o.setFeedbackStatus(status);
        o.setFeedbackNote(StringUtils.hasText(note) ? note.trim() : null);
        o.setFeedbackBy(AuthContext.userId());
        o.setFeedbackByName(AuthContext.userName());
        o.setFeedbackAt(LocalDateTime.now());
        opinionMapper.updateById(o);

        logService.log("CASE", "OPINION_FEEDBACK", "CASE", o.getCaseId(),
                "反馈意见落实情况[" + statusName(status) + "]：" + abbrev(o.getContent())
                        + (StringUtils.hasText(note) ? "（" + abbrev(note) + "）" : ""));
        return o;
    }

    // ------------------------------------------------------------------

    private CaseLeaderOpinion requireOpinion(Long id) {
        CaseLeaderOpinion o = id == null ? null : opinionMapper.selectById(id);
        if (o == null) {
            throw new BizException("意见不存在或已被删除");
        }
        return o;
    }

    private void requireCase(Long caseId) {
        if (caseId == null || caseMapper.selectById(caseId) == null) {
            throw new BizException("案件不存在");
        }
    }

    private String statusName(String status) {
        if (CaseLeaderOpinion.FB_DONE.equals(status)) {
            return "完成";
        }
        if (CaseLeaderOpinion.FB_IN_PROGRESS.equals(status)) {
            return "进行中";
        }
        return "未完成";
    }

    private String abbrev(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
