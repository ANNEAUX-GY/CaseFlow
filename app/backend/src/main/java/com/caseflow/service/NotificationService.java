package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseNotification;
import com.caseflow.entity.OperationLog;
import com.caseflow.entity.SysUser;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseNotificationMapper;
import com.caseflow.mapper.SysUserMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.security.Roles;
import com.caseflow.support.SseHub;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一信箱：把"与自己有关的操作变更"归入 {@link CaseNotification}。
 *
 * <p><b>唯一埋点</b>：{@code LogService.log} 落库后调用 {@link #onLog(OperationLog)}，
 * 全站所有写操作自动进信箱，无需各模块单独埋。
 *
 * <p><b>分发口径</b>（用户 2026-10-04 确认）：
 * <ul>
 *   <li><b>普通用户</b>：本人承办案件（ACTIVE 主办/协办）相关的操作；</li>
 *   <li><b>管理层</b>（BOSS/CHIEF/DEPUTY_CHIEF/LAW_OFFICER）：
 *       全站<b>所有用户</b>的操作（除自己触发的）——他们要总览全局；</li>
 *   <li><b>任何人不收自己触发的操作</b>（自己改的自己知道，别给自己发垃圾）。</li>
 * </ul>
 *
 * <p><b>白名单</b>：只通知"有意义的业务变更"（意见/指派/状态/待办/文件），
 * 登录、查询、字典等噪声不入箱。
 */
@Service
public class NotificationService {

    /** 进信箱的动作白名单（真实的 module_action，见全项目 logService.log 枚举）。
     *  排除纯内部噪声：OPINION_REORDER（拖拽排序）、OPINION_NOTICE（系统提醒本身）、
     *  TODO_REORDER、UNDO（撤回另计）。其余案件类业务变更全进。 */
    private static final Set<String> NOTIFY_ACTIONS = new HashSet<>();

    static {
        // 领导意见
        NOTIFY_ACTIONS.add("CASE_OPINION_ADD");
        NOTIFY_ACTIONS.add("CASE_OPINION_UPDATE_CONTENT");
        NOTIFY_ACTIONS.add("CASE_OPINION_UPDATE_META");
        NOTIFY_ACTIONS.add("CASE_OPINION_REMOVE");
        NOTIFY_ACTIONS.add("CASE_OPINION_FEEDBACK");
        // 指派 / 状态流转
        NOTIFY_ACTIONS.add("CASE_ASSIGN");
        NOTIFY_ACTIONS.add("CASE_STATUS");
        NOTIFY_ACTIONS.add("CASE_FLOW_TRANSFER");
        // 待办 / 子任务
        NOTIFY_ACTIONS.add("CASE_TODO_ADD");
        NOTIFY_ACTIONS.add("CASE_TODO_DONE");
        NOTIFY_ACTIONS.add("CASE_TODO_FEEDBACK");
        NOTIFY_ACTIONS.add("CASE_TODO_DELETE");
        NOTIFY_ACTIONS.add("CASE_TODO_SUBTASK_ADD");
        NOTIFY_ACTIONS.add("CASE_TODO_SUBTASK_REOPEN");
        // 疑问
        NOTIFY_ACTIONS.add("CASE_QUESTION_ASK");
        NOTIFY_ACTIONS.add("CASE_QUESTION_ANSWER");
        // 计划 / 措施 / 侦查
        NOTIFY_ACTIONS.add("CASE_PLAN_ADD");
        NOTIFY_ACTIONS.add("CASE_PLAN_DONE");
        NOTIFY_ACTIONS.add("CASE_PLAN_CANCEL");
        NOTIFY_ACTIONS.add("CASE_MEASURE");
        NOTIFY_ACTIONS.add("CASE_INVESTIGATION");
        // 附件
        NOTIFY_ACTIONS.add("FILE_UPLOAD");
        NOTIFY_ACTIONS.add("FILE_DELETE");
        // 嫌疑人 / 批注
        NOTIFY_ACTIONS.add("CASE_SUSPECT_ADD");
        NOTIFY_ACTIONS.add("CASE_SUSPECT_UPDATE");
        NOTIFY_ACTIONS.add("CASE_SUSPECT_DELETE");
        NOTIFY_ACTIONS.add("CASE_COMMENT_ADD");
        NOTIFY_ACTIONS.add("CASE_COMMENT_UPDATE");
        NOTIFY_ACTIONS.add("CASE_COMMENT_DELETE");
    }

    @Resource
    private CaseNotificationMapper notificationMapper;
    @Resource
    private CaseAssigneeMapper assigneeMapper;
    @Resource
    private SysUserMapper userMapper;
    @Resource
    private com.caseflow.mapper.CaseInfoMapper caseMapper;
    @Resource
    private SseHub sseHub;

    /**
     * 由 LogService 在日志落库后调用（唯一埋点）。
     * 幂等：logId 相同不会重复派生（用 log_id 去重）。
     *
     * @param anchor 定位锚点（可空）：让点通知能直达待办详情/某条疑问，
     *               而不只跳到案件详情页。为空则降级为案件级定位。
     */
    public void onLog(OperationLog log) {
        onLog(log, null);
    }

    public void onLog(OperationLog log, com.caseflow.support.LogService.Anchor anchor) {
        if (log == null) {
            return;
        }
        // 只处理案件类目标（targetType=CASE），非案件类操作没有"与谁有关"的归属
        if (!"CASE".equals(log.getTargetType()) || log.getTargetId() == null) {
            return;
        }
        String key = log.getModule() + "_" + log.getAction();
        if (!isNotifyAction(key)) {
            return;
        }
        // 幂等：同一条日志已派生过就不重复
        Long already = notificationMapper.selectCount(new LambdaQueryWrapper<CaseNotification>()
                .eq(CaseNotification::getLogId, log.getId()));
        if (already != null && already > 0) {
            return;
        }

        Set<Long> recipients = resolveRecipients(log);
        if (recipients.isEmpty()) {
            return;
        }
        String title = buildTitle(log);
        String type = mapType(log.getModule(), log.getAction());
        for (Long uid : recipients) {
            CaseNotification n = new CaseNotification();
            n.setUserId(uid);
            n.setCaseId(log.getTargetId());
            n.setLogId(log.getId());
            // 定位锚点：业务侧告知目标对象ID，使点通知能直达具体内容
            if (anchor != null) {
                n.setAnchorTodoId(anchor.todoId);
                n.setAnchorQuestionId(anchor.questionId);
                n.setAnchorSubtaskId(anchor.subtaskId);
            }
            n.setType(type);
            n.setTitle(title);
            n.setContent(log.getContent());
            n.setCreatedAt(LocalDateTime.now());
            notificationMapper.insert(n);
            // 定向实时提醒：前端按当前登录 userId 过滤
            sseHub.broadcast(notificationEvent(uid, n));
        }
    }

    /** 我的未读通知（新→旧） */
    public List<CaseNotification> unreadForMe() {
        Long uid = AuthContext.userId();
        if (uid == null) {
            return new ArrayList<>();
        }
        return notificationMapper.selectList(new LambdaQueryWrapper<CaseNotification>()
                .eq(CaseNotification::getUserId, uid)
                .isNull(CaseNotification::getReadAt)
                .orderByDesc(CaseNotification::getCreatedAt)
                .orderByDesc(CaseNotification::getId));
    }

    /** 我的信箱列表（新→旧）：box=unread 未读 / read 已读 / all 全部。
     *  <p>已读消息保留在库（read_at 非空，不删行），信箱要能翻历史（2026-10-06 需求）。
     *  拉取封顶 200 条：管理层收全站操作，量会一直涨，下拉面板不需要全量。 */
    public List<CaseNotification> listForMe(String box) {
        Long uid = AuthContext.userId();
        if (uid == null) {
            return new ArrayList<>();
        }
        String b = box == null ? "all" : box.toLowerCase();
        LambdaQueryWrapper<CaseNotification> q = new LambdaQueryWrapper<CaseNotification>()
                .eq(CaseNotification::getUserId, uid)
                .orderByDesc(CaseNotification::getCreatedAt)
                .orderByDesc(CaseNotification::getId);
        if ("unread".equals(b)) {
            q.isNull(CaseNotification::getReadAt);
        } else if ("read".equals(b)) {
            q.isNotNull(CaseNotification::getReadAt);
        }
        q.last("LIMIT 200");
        return notificationMapper.selectList(q);
    }

    /** 未读数（顶栏角标用，走 count 不拉全量） */
    public int unreadCount() {
        Long uid = AuthContext.userId();
        if (uid == null) {
            return 0;
        }
        Long c = notificationMapper.selectCount(new LambdaQueryWrapper<CaseNotification>()
                .eq(CaseNotification::getUserId, uid)
                .isNull(CaseNotification::getReadAt));
        return c == null ? 0 : c.intValue();
    }

    /** 标记单条已读（幂等） */
    public void markRead(Long id) {
        Long uid = AuthContext.userId();
        if (uid == null || id == null) {
            return;
        }
        CaseNotification n = notificationMapper.selectById(id);
        if (n == null || !uid.equals(n.getUserId()) || n.getReadAt() != null) {
            return;
        }
        CaseNotification patch = new CaseNotification();
        patch.setId(id);
        patch.setReadAt(LocalDateTime.now());
        notificationMapper.updateById(patch);
    }

    /** 全部标为已读，返回本次清掉的条数 */
    public int markAllRead() {
        Long uid = AuthContext.userId();
        if (uid == null) {
            return 0;
        }
        List<CaseNotification> list = notificationMapper.selectList(new LambdaQueryWrapper<CaseNotification>()
                .eq(CaseNotification::getUserId, uid)
                .isNull(CaseNotification::getReadAt));
        int n = 0;
        for (CaseNotification x : list) {
            CaseNotification patch = new CaseNotification();
            patch.setId(x.getId());
            patch.setReadAt(LocalDateTime.now());
            notificationMapper.updateById(patch);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 私有
    // ------------------------------------------------------------------

    /** 解析收件人（按**收件人**维度，而非操作人维度）：
     *  <ul>
     *    <li>普通用户：只收「本人承办案件相关」的操作（ACTIVE 主办/协办）；</li>
     *    <li>管理层：收**所有**操作（全站视角），但不收自己触发的；</li>
     *    <li>任何人不收自己触发的。</li>
     *  </ul>
     *  用户规则原文：「普通用户是与自己有关的操作，管理用户接收所有普通用户的操作
     *  以及其他管理用户的操作」——即管理层视角 = 全站除自己。
     */
    private Set<Long> resolveRecipients(OperationLog log) {
        Set<Long> out = new HashSet<>();
        Long operatorId = log.getOperatorId();

        // 1) 普通用户：本案现役承办人+协办人（除自己）
        List<CaseAssignee> as = assigneeMapper.selectList(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getCaseId, log.getTargetId())
                .eq(CaseAssignee::getStatus, "ACTIVE"));
        for (CaseAssignee a : as) {
            if (a.getEmployeeId() == null) {
                continue;
            }
            Long uid = userIdOfEmployee(a.getEmployeeId());
            if (uid == null || uid.equals(operatorId)) {
                continue;
            }
            out.add(uid);
        }

        // 2) 管理层：全站所有用户（除操作人自己）——他们要总览全局
        List<SysUser> all = userMapper.selectList(new LambdaQueryWrapper<SysUser>());
        for (SysUser u : all) {
            if (u.getId() == null || u.getId().equals(operatorId)) {
                continue;
            }
            if (Roles.isFullAccess(u.getRole())) {
                out.add(u.getId());
            }
        }
        return out;
    }

    /** 按员工档案 id 反查登录账号 userId；找不到返回 null（该员工可能没开账号） */
    private Long userIdOfEmployee(Long employeeId) {
        SysUser u = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmployeeId, employeeId));
        return u == null ? null : u.getId();
    }

    /** 从 module+action 映射为简短的 type 标签（用完整 key 判断，稳） */
    private String mapType(String module, String action) {
        String key = (module == null ? "" : module) + "_" + (action == null ? "" : action);
        if (key.contains("OPINION")) return "OPINION";
        if (key.contains("ASSIGN") || key.contains("TRANSFER")) return "ASSIGN";
        if (key.contains("STATUS") || key.contains("MEASURE") || key.contains("INVESTIGATION")
                || key.contains("PLAN") || key.contains("FLOW")) return "STATUS";
        if (key.contains("TODO")) return "TODO";
        if (key.contains("QUESTION")) return "QUESTION";
        if (key.contains("SUSPECT") || key.contains("COMMENT")) return "OTHER";
        if (key.startsWith("FILE_")) return "FILE";
        return "OTHER";
    }

    /** 动作是否进信箱（module_action 精确匹配白名单） */
    private boolean isNotifyAction(String key) {
        return NOTIFY_ACTIONS.contains(key);
    }

    /** 摘要标题：用 actionName 语义 + 操作人，避免生硬拼接 */
    private String buildTitle(OperationLog log) {
        String who = log.getOperatorName() == null ? "" : log.getOperatorName();
        String act = actionLabel(log.getModule(), log.getAction());
        return who.isEmpty() ? act : who + " " + act;
    }

    /** 动作中文标签（信箱标题用，与 actionName 口径一致即可，独立维护避免耦合） */
    private String actionLabel(String module, String action) {
        String key = (module == null ? "" : module) + "_" + (action == null ? "" : action);
        if (key.contains("ASSIGN")) return "调整了指派";
        if (key.contains("TRANSFER")) return "转手了案件";
        if (key.contains("STATUS") || key.contains("FLOW")) return "更新了案件状态";
        if (key.contains("OPINION")) return "更新了领导意见";
        if (key.contains("TODO")) return "更新了待办";
        if (key.contains("QUESTION")) return "更新了疑问";
        if (key.contains("PLAN")) return "更新了办理计划";
        if (key.contains("MEASURE") || key.contains("INVESTIGATION")) return "更新了案件进度";
        if (key.contains("SUSPECT")) return "更新了嫌疑人信息";
        if (key.contains("COMMENT")) return "新增了批注";
        if (key.startsWith("FILE_")) return "更新了案件材料";
        return "更新了案件";
    }

    // ------------------------------------------------------------------
    // 悬空信件清理（2026-10-11）
    // ------------------------------------------------------------------

    /**
     * 删掉某个案件的全部信件（删除案件时调用）。
     *
     * <p>为什么必须删：信件里存的是 case_id 与定位锚点，案件一删这些信息就成了死链——
     * 用户点「查看案件」只会得到一句"案件不存在"（本次实测：18 条信件里 6 条指向已删案件，
     * 全是自检脚本留下的）。留着它们等于让信箱里永远躺着一批点不开的信。
     *
     * <p><b>不进快照/撤回体系</b>：信件是通知副本，撤回案件时由 {@code onLog} 重新生成一条
     * 「删除案件」的信件即可，把旧信件还原回来反而会制造第二条死链。
     */
    @Transactional(rollbackFor = Exception.class)
    public int removeAllOfCase(Long caseId) {
        if (caseId == null) {
            return 0;
        }
        return notificationMapper.delete(new LambdaQueryWrapper<CaseNotification>()
                .eq(CaseNotification::getCaseId, caseId));
    }

    /**
     * 清掉「所指案件已经不在了」的信件（启动时跑一次，幂等）。
     *
     * <p>这是给存量数据擦屁股的：在 {@code removeAllOfCase} 上线之前删掉的案件，
     * 它们的信件还留在库里。不做这一步，用户今天打开信箱照样点得进去一片空白。
     *
     * <p>只在启动时跑，不在每次查询时过滤——后者会让"信箱条数"与"未读数"两套口径
     * 反复打架，而信件失效是一次性的事实，清掉就完了。
     *
     * @return 清掉的条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int purgeOrphan() {
        List<CaseNotification> all = notificationMapper.selectList(new LambdaQueryWrapper<CaseNotification>()
                .select(CaseNotification::getId, CaseNotification::getCaseId)
                .isNotNull(CaseNotification::getCaseId));
        if (all.isEmpty()) {
            return 0;
        }
        Set<Long> caseIds = new HashSet<>();
        for (CaseNotification n : all) {
            caseIds.add(n.getCaseId());
        }
        Set<Long> alive = new HashSet<>();
        for (com.caseflow.entity.CaseInfo c : caseMapper.selectBatchIds(caseIds)) {
            alive.add(c.getId());
        }
        List<Long> dead = new ArrayList<>();
        for (CaseNotification n : all) {
            if (!alive.contains(n.getCaseId())) {
                dead.add(n.getId());
            }
        }
        if (dead.isEmpty()) {
            return 0;
        }
        notificationMapper.deleteBatchIds(dead);
        return dead.size();
    }

    private Map<String, Object> notificationEvent(Long userId, CaseNotification n) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("kind", "notification");
        event.put("userId", userId);
        event.put("id", n.getId());
        event.put("caseId", n.getCaseId());
        // 定位锚点一并下发：实时到达的通知也能直达具体内容
        event.put("anchorTodoId", n.getAnchorTodoId());
        event.put("anchorQuestionId", n.getAnchorQuestionId());
        event.put("anchorSubtaskId", n.getAnchorSubtaskId());
        event.put("type", n.getType());
        event.put("title", n.getTitle());
        event.put("content", n.getContent());
        event.put("createdAt", n.getCreatedAt() == null ? null : n.getCreatedAt().toString());
        return event;
    }
}
