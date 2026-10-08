package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseQuestion;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CaseQuestionMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.LogService;
import javax.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 疑问问答（2026-10-04）：员工提问、管理层回答。
 *
 * <p>权限口径与待办一致：提问=承办人或管理层（{@link #checkOperate}），
 * 回答=仅管理层。**刻意不与待办联动**——问答不派生待办、不进完成规则、
 * 不进反馈流，它只是沟通记录（用户明确要求独立于待办和任务）。
 */
@Service
public class QuestionService {

    @Resource
    private CaseQuestionMapper questionMapper;
    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CaseAssigneeMapper assigneeMapper;
    @Resource
    private LogService logService;

    /** 某案件的问答列表，时间正序（老问题在前，贴合"往下翻历史"的阅读习惯） */
    public List<CaseQuestion> listOfCase(Long caseId) {
        if (caseId == null) {
            return new java.util.ArrayList<>();
        }
        return questionMapper.selectList(new LambdaQueryWrapper<CaseQuestion>()
                .eq(CaseQuestion::getCaseId, caseId)
                .orderByAsc(CaseQuestion::getCreatedAt)
                .orderByAsc(CaseQuestion::getId));
    }

    /** 某任务的问答（仅上下文过滤，供浮窗按任务查看；不筛也返回全案） */
    public List<CaseQuestion> listOfTodo(Long todoId) {
        if (todoId == null) {
            return new java.util.ArrayList<>();
        }
        return questionMapper.selectList(new LambdaQueryWrapper<CaseQuestion>()
                .eq(CaseQuestion::getTodoId, todoId)
                .orderByAsc(CaseQuestion::getCreatedAt)
                .orderByAsc(CaseQuestion::getId));
    }

    /** 提问：承办人或管理层均可 */
    @Transactional(rollbackFor = Exception.class)
    public CaseQuestion ask(Long caseId, Long todoId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写问题内容");
        }
        CaseInfo c = requireCase(caseId);
        checkOperate(c);

        CaseQuestion q = new CaseQuestion();
        q.setCaseId(caseId);
        q.setTodoId(todoId);
        q.setContent(content.trim());
        q.setQuestionBy(AuthContext.userId());
        q.setQuestionByName(AuthContext.userName());
        q.setCreatedAt(LocalDateTime.now());
        questionMapper.insert(q);

        // 锚点带上 todoId+questionId：点通知直达该条疑问，
        // 落地时由前端打开任务详情浮窗并滚动定位到这一条
        logService.logAnchored("CASE", "QUESTION_ASK", "CASE", caseId,
                "提出疑问：" + abbrev(content), null, null,
                LogService.Anchor.ofQuestion(todoId, q.getId()));
        return q;
    }

    /** 回答：仅管理层。一问一答；回答后不可再改（要更正就再发一条回答说明） */
    @Transactional(rollbackFor = Exception.class)
    public CaseQuestion answer(Long questionId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写回答内容");
        }
        CaseQuestion q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BizException(400, "该疑问不存在或已被删除");
        }
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "只有管理员或领导可以回答疑问");
        }
        if (StringUtils.hasText(q.getAnswer())) {
            throw new BizException("该疑问已回答过，请直接再提一条新问题说明更正内容");
        }

        String before = snapshot(q);
        q.setAnswer(content.trim());
        q.setAnswerBy(AuthContext.userId());
        q.setAnswerByName(AuthContext.userName());
        q.setAnsweredAt(LocalDateTime.now());
        questionMapper.updateById(q);

        logService.logAnchored("CASE", "QUESTION_ANSWER", "CASE", q.getCaseId(),
                "回答疑问：" + abbrev(q.getContent()) + " → " + abbrev(content), before, snapshot(q),
                LogService.Anchor.ofQuestion(q.getTodoId(), q.getId()));
        return q;
    }

    /**
     * 修订已给出的回答：仅管理层（2026-10-08）。
     *
     * <p><b>为什么单独一个方法而不是复用 {@link #answer}</b>：answer() 的
     * 「一问一答，回答后不可再改」是针对<b>首次回答</b>的约束（防重复刷答）；
     * 而管理层答错了内容需要能改，两者是不同语义，不能共用一个入口。
     *
     * <p><b>回答人与首次回答时间保持原样</b>，修订信息另存
     * answerEditedBy/answerEditedByName/answerEditedAt 三列——
     * 否则「谁最初答的」会被改答人抹掉，争议时无从追溯。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseQuestion updateAnswer(Long questionId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写回答内容");
        }
        CaseQuestion q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BizException(400, "该疑问不存在或已被删除");
        }
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "只有管理员或领导可以修改回答");
        }
        if (!StringUtils.hasText(q.getAnswer())) {
            throw new BizException("该疑问尚未回答，请直接回答而不是修订");
        }
        String newText = content.trim();
        if (newText.equals(q.getAnswer().trim())) {
            return q;   // 内容没变就别刷「已修订」痕迹
        }

        String before = snapshot(q);
        String oldText = q.getAnswer();
        q.setAnswer(newText);
        q.setAnswerEditedBy(AuthContext.userId());
        q.setAnswerEditedByName(AuthContext.userName());
        q.setAnswerEditedAt(LocalDateTime.now());
        questionMapper.updateById(q);

        // 摘要里用 oldText（setAnswer 之前的值），否则新旧对照会都显示新内容
        logService.logAnchored("CASE", "QUESTION_ANSWER_UPDATE", "CASE", q.getCaseId(),
                "修订回答：" + abbrev(q.getContent())
                        + "（原「" + abbrev(oldText) + "」→「" + abbrev(newText) + "」）",
                before, snapshot(q),
                LogService.Anchor.ofQuestion(q.getTodoId(), q.getId()));
        return q;
    }

    /** 编辑问题：仅提问人本人或管理层；已回答的问题允许改，但会标记需重新确认 */
    @Transactional(rollbackFor = Exception.class)
    public CaseQuestion update(Long questionId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写问题内容");
        }
        CaseQuestion q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BizException(400, "该疑问不存在或已被删除");
        }
        requireOwnerOrManager(q);
        q.setContent(content.trim());
        questionMapper.updateById(q);
        logService.logAnchored("CASE", "QUESTION_UPDATE", "CASE", q.getCaseId(),
                "编辑疑问：" + abbrev(content), null, null,
                LogService.Anchor.ofQuestion(q.getTodoId(), q.getId()));
        return q;
    }

    /** 删除问题：仅提问人本人或管理层；连同回答一起删（问答一体） */
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long questionId) {
        CaseQuestion q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BizException(400, "该疑问不存在或已被删除");
        }
        requireOwnerOrManager(q);
        questionMapper.deleteById(questionId);
        logService.logAnchored("CASE", "QUESTION_DELETE", "CASE", q.getCaseId(),
                "删除疑问：" + abbrev(q.getContent()), null, null,
                LogService.Anchor.ofQuestion(q.getTodoId(), q.getId()));
    }

    /** 是否提问人本人或管理层 */
    private void requireOwnerOrManager(CaseQuestion q) {
        if (AuthContext.isFullAccess()) {
            return;
        }
        Long uid = AuthContext.userId();
        if (uid != null && uid.equals(q.getQuestionBy())) {
            return;
        }
        throw new BizException(403, "只有提问人本人或管理层可以编辑/删除这条疑问");
    }

    // ------------------------------------------------------------------

    private CaseInfo requireCase(Long caseId) {
        if (caseId == null || caseMapper.selectById(caseId) == null) {
            throw new BizException(400, "案件不存在");
        }
        return caseMapper.selectById(caseId);
    }

    /** 与待办同口径：管理层放行，否则必须是本案现职承办人 */
    private void checkOperate(CaseInfo c) {
        if (AuthContext.isFullAccess()) {
            return;
        }
        Long empId = AuthContext.get() == null ? null : AuthContext.get().getEmployeeId();
        if (empId == null) {
            throw new BizException(403, "只有案件承办人或管理层可以提问");
        }
        Long cnt = assigneeMapper.selectCount(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getCaseId, c.getId())
                .eq(CaseAssignee::getEmployeeId, empId)
                .eq(CaseAssignee::getStatus, "ACTIVE"));
        if (cnt == null || cnt == 0) {
            throw new BizException(403, "只有案件承办人或管理层可以提问");
        }
    }

    private String snapshot(CaseQuestion q) {
        return q.getId() + "|" + (q.getAnswer() == null ? "" : q.getAnswer());
    }

    private String abbrev(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }
}
