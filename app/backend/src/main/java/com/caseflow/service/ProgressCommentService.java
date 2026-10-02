package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseProgressComment;
import com.caseflow.entity.OperationLog;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CaseProgressCommentMapper;
import com.caseflow.mapper.OperationLogMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.LogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 办理进度批注（类似 Word 批注）。
 *
 * <p>权限模型：
 * <ul>
 *   <li>新增 / 编辑 / 删除 = <b>管理层</b>（管理员 BOSS + 领导 CHIEF/DEPUTY_CHIEF/LAW_OFFICER）；</li>
 *   <li>查看 = 所有能查看该案件详情的人（办案人、普通用户均可读）。</li>
 * </ul>
 *
 * <p>批注挂在办理进度（{@link OperationLog}）上，展示批注人、批注时间与关联进度。
 * 批注不进案件快照体系（撤回进度不连带回滚批注），但增删改全部埋点进时间线。
 */
@Service
public class ProgressCommentService {

    @Resource
    private CaseProgressCommentMapper commentMapper;
    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private OperationLogMapper logMapper;
    @Resource
    private LogService logService;

    /** 某案件的全部批注（按进度、时间排序，前端按 logId 分组挂到时间线上） */
    public List<CaseProgressComment> listOf(Long caseId) {
        return commentMapper.selectList(new LambdaQueryWrapper<CaseProgressComment>()
                .eq(CaseProgressComment::getCaseId, caseId)
                .orderByAsc(CaseProgressComment::getLogId)
                .orderByAsc(CaseProgressComment::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public CaseProgressComment add(Long logId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写批注内容");
        }
        requireManager("添加批注");
        OperationLog target = requireLog(logId);
        Long caseId = target.getTargetId();
        requireCase(caseId);

        CaseProgressComment c = new CaseProgressComment();
        c.setCaseId(caseId);
        c.setLogId(logId);
        c.setContent(content.trim());
        c.setCreatorId(AuthContext.userId());
        c.setCreatorName(AuthContext.userName());
        c.setCreatedAt(LocalDateTime.now());
        commentMapper.insert(c);

        logService.log("CASE", "COMMENT_ADD", "CASE", caseId,
                "批注办理进度「" + abbrev(target.getContent()) + "」：" + abbrev(c.getContent()));
        return c;
    }

    @Transactional(rollbackFor = Exception.class)
    public CaseProgressComment update(Long id, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写批注内容");
        }
        requireManager("编辑批注");
        CaseProgressComment c = requireComment(id);
        String old = c.getContent();
        c.setContent(content.trim());
        c.setUpdatedAt(LocalDateTime.now());
        c.setUpdaterName(AuthContext.userName());
        commentMapper.updateById(c);

        logService.log("CASE", "COMMENT_UPDATE", "CASE", c.getCaseId(),
                "编辑批注：" + abbrev(old) + " → " + abbrev(c.getContent()));
        return c;
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        requireManager("删除批注");
        CaseProgressComment c = requireComment(id);
        commentMapper.deleteById(id);
        logService.log("CASE", "COMMENT_DELETE", "CASE", c.getCaseId(),
                "删除批注：" + abbrev(c.getContent()));
    }

    // ------------------------------------------------------------------

    /** 批注写操作 = 管理层（管理员 + 领导） */
    private void requireManager(String what) {
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "只有管理员或领导可以" + what);
        }
    }

    private OperationLog requireLog(Long logId) {
        OperationLog l = logId == null ? null : logMapper.selectById(logId);
        if (l == null) {
            throw new BizException("要批注的进度记录不存在");
        }
        return l;
    }

    private CaseProgressComment requireComment(Long id) {
        CaseProgressComment c = id == null ? null : commentMapper.selectById(id);
        if (c == null) {
            throw new BizException("批注不存在或已被删除");
        }
        return c;
    }

    private void requireCase(Long caseId) {
        if (caseId == null || caseMapper.selectById(caseId) == null) {
            throw new BizException("案件不存在");
        }
    }

    private String abbrev(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
