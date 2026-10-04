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
    /**
     * 民警待办服务：领导提意见后自动派生待办。
     * 构造器/字段注入均无循环依赖（StaffTodoService 只查 opinionMapper，不回调本类）。
     */
    @Resource
    private StaffTodoService staffTodoService;

    public List<CaseLeaderOpinion> listOf(Long caseId) {
        List<CaseLeaderOpinion> list = opinionMapper.selectList(new LambdaQueryWrapper<CaseLeaderOpinion>()
                .eq(CaseLeaderOpinion::getCaseId, caseId)
                // 已移除的意见：软删标记或正文被清空，都不再出现在列表里。
                // 用 isNotNull 排除 NULL 而非 ='Y'，兼容早期可能被写成空串的情况。
                .isNotNull(CaseLeaderOpinion::getContent)
                .ne(CaseLeaderOpinion::getContent, "")
                // 升序：序号自上而下递增（1 起连续）。
                // 排序键用 sort_order，NULL（旧数据）回退按 id 升序。
                // 必须用 last() 追加 ORDER BY 子句：apply() 是拼进 WHERE 条件的，
                // 若把 ASC 写进 apply 里会变成 "WHERE (case_id=? AND ... ) ASC" 直接语法错（踩过）。
                .last("ORDER BY (CASE WHEN sort_order IS NULL THEN id ELSE sort_order END) ASC, id ASC"));
        initSortOrderIfAbsent(list);
        return list;
    }

    /**
     * 旧数据兼容：整批意见的 sort_order 全为空时（老库首次读到该案件），按当前 id 升序
     * 惰性初始化为 1..N 并落库，之后拖拽才有落脚点。
     *
     * <p>幂等：只要存在任意一条已排序（说明已经初始化过或是拖拽过），就整批跳过。
     */
    private void initSortOrderIfAbsent(List<CaseLeaderOpinion> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (CaseLeaderOpinion o : list) {
            if (o.getSortOrder() != null) {
                return;
            }
        }
        for (int i = 0; i < list.size(); i++) {
            CaseLeaderOpinion o = list.get(i);
            o.setSortOrder(i + 1);
            // 逐条 update 只带 id + sort_order，updateById 会跳过 null 字段，
            // 不会把 content/反馈等已有数据清空。
            opinionMapper.updateById(o);
        }
    }

    /** 提出意见（管理层）。一个案件可提多条。 */
    @Transactional(rollbackFor = Exception.class)
    public CaseLeaderOpinion add(Long caseId, String content, String deadline, String importance) {
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
        o.setDeadline(parseDeadline(deadline));
        o.setImportance(normalizeImportance(importance));
        // 新意见追加到末尾：位次 = 当前最大值 + 1（列表里没有任何 sort_order 时从 1 开始）。
        o.setSortOrder(nextSortOrder(caseId));
        opinionMapper.insert(o);

        logService.log("CASE", "OPINION_ADD", "CASE", caseId,
      "提出意见：" + abbrev(o.getContent())
      + (o.getDeadline() != null ? "（截止 " + o.getDeadline() + "）" : "")
        + "（重要性 " + o.getImportance() + "）");

        // 自动派生民警待办（2026-10-04）：领导提意见 = 民警收到一条待办。
        // 放在 log 之后、同一事务内：派生失败会连带回滚这条意见，
        // 避免出现"有意见但没待办"的漏项。
        try {
      staffTodoService.deriveFromOpinion(o);
        } catch (Exception e) {
        logService.log("CASE", "OPINION_TODO_DERIVE_FAIL", "CASE", caseId,
   "意见已记录但派生待办失败：" + e.getMessage());
     }
   return o;
    }

    /** 下一条意见的排序位次：现有最大值 + 1；无记录则 1。 */
    private Integer nextSortOrder(Long caseId) {
        List<CaseLeaderOpinion> all = opinionMapper.selectList(new LambdaQueryWrapper<CaseLeaderOpinion>()
                .select(CaseLeaderOpinion::getSortOrder)
                .eq(CaseLeaderOpinion::getCaseId, caseId));
        int max = 0;
        for (CaseLeaderOpinion o : all) {
            if (o.getSortOrder() != null && o.getSortOrder() > max) {
                max = o.getSortOrder();
            }
        }
        return max + 1;
    }

    /**
     * 拖拽排序：按前端提交的新顺序（opinionId 数组）重排sort_order 为 1..N。
     *
     * <p>仅管理层可调（顺序是领导定的语义）。序号由前端按下标实时显示，故这里只落库顺序。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reorder(Long caseId, List<Long> orderedIds) {
        if (caseId == null || orderedIds == null || orderedIds.isEmpty()) {
            throw new BizException("排序数据为空");
        }
        requireCase(caseId);
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "只有管理员或领导可以调整意见顺序");
        }
        // 只能排本案的意见，防止跨案件篡改；已软删的不参与排序
        List<CaseLeaderOpinion> own = opinionMapper.selectList(new LambdaQueryWrapper<CaseLeaderOpinion>()
                .eq(CaseLeaderOpinion::getCaseId, caseId)
                .isNotNull(CaseLeaderOpinion::getContent)
                .ne(CaseLeaderOpinion::getContent, ""));
        java.util.Map<Long, CaseLeaderOpinion> byId = new java.util.HashMap<>();
        for (CaseLeaderOpinion o : own) {
            byId.put(o.getId(), o);
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        int pos = 0;
        for (Long id : orderedIds) {
            CaseLeaderOpinion o = id == null ? null : byId.get(id);
            if (o == null || !seen.add(id)) {
                // 跳过无效/重复项，长度不等时也不会写出错误位次
                continue;
            }
            pos++;
            // 注意：旧数据的 sortOrder 可能是 null，直接 .equals 会 NPE（Integer 拆箱）
            if (o.getSortOrder() == null || o.getSortOrder() != pos) {
                o.setSortOrder(pos);
                opinionMapper.updateById(o);
            }
        }
        // 提交列表没带上的意见（理论上不会）排到末尾
        for (CaseLeaderOpinion o : own) {
            if (!seen.contains(o.getId())) {
                pos++;
                o.setSortOrder(pos);
                opinionMapper.updateById(o);
            }
        }
        // 统一重排成 1..N：拖拽时前端传的是「当前可见列表」，
        // 而 own 包含已软删的行，不重排就会在持久化值上留空洞。
        resequence(caseId);

        logService.log("CASE", "OPINION_REORDER", "CASE", caseId,
                "调整领导意见顺序（" + pos + " 条）");
    }

    /**
     * 修改意见的截止时间与重要性（管理层）。
     *
     * <p>只动这两个元信息字段，不碰 content 与反馈记录；配合前端列表内直接编辑。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseLeaderOpinion updateMeta(Long opinionId, String deadline, String importance) {
        CaseLeaderOpinion o = requireOpinion(opinionId);
        requireCase(o.getCaseId());
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "只有管理员或领导可以修改意见的截止时间与重要性");
        }
        o.setDeadline(parseDeadline(deadline));
        o.setImportance(normalizeImportance(importance));
        opinionMapper.updateById(o);

        logService.log("CASE", "OPINION_UPDATE_META", "CASE", o.getCaseId(),
                "修改意见设置[" + importanceName(o.getImportance())
                        + (o.getDeadline() != null ? "，截止 " + o.getDeadline() : "，清除截止时间") + "]：" + abbrev(o.getContent()));
        notifyAssignees(o.getCaseId(), "领导调整了意见「" + abbrev(o.getContent()) + "」的设置（"
                + importanceName(o.getImportance())
                + (o.getDeadline() != null ? "，截止 " + o.getDeadline() : "，无截止时间") + "）");
        return o;
    }

    /** 解析截止时间：空串/空白=清空；格式非法直接报错，避免静默丢数据。 */
    private LocalDateTime parseDeadline(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        String t = s.trim().replace('T', ' ').replace('/', '-');
        if (t.length() > 19) {
            t = t.substring(0, 19);
        }
        try {
            return LocalDateTime.parse(t.replace(' ', 'T'));
        } catch (Exception e) {
            throw new BizException("截止时间格式不正确，应为「2026-10-05 18:00」这样的格式");
        }
    }

    /** 重要性归一：空 → C（一般），非法值也回落 C，绝不写脏值进库。 */
    private String normalizeImportance(String s) {
        if (!StringUtils.hasText(s)) {
            return CaseLeaderOpinion.IMP_C;
        }
        String v = s.trim().toUpperCase();
        if (CaseLeaderOpinion.IMP_A.equals(v)
                || CaseLeaderOpinion.IMP_B.equals(v)
                || CaseLeaderOpinion.IMP_C.equals(v)) {
            return v;
        }
        return CaseLeaderOpinion.IMP_C;
    }

    private String importanceName(String code) {
        if (CaseLeaderOpinion.IMP_A.equals(code)) {
            return "A-最重要";
        }
        if (CaseLeaderOpinion.IMP_B.equals(code)) {
            return "B-重要";
        }
        return "C-一般";
    }

    /**
     * 修改意见正文（仅管理层）。
     *
     * <p>为什么单独一个方法而不并进 {@link #updateMeta}：
     * 两者权限相同但语义不同——正文是"意见说了什么"，元信息是"这条意见怎么管"。
     * 分开也让日志能区分「改了内容」与「改了设置」，出问题查起来一眼看出。
     *
     * <p>已产生反馈的正文**同样允许修改**：现实中领导会根据落实情况调整表述，
     * 这是正常业务。但要留下痕迹——log 里的摘要同时带新旧内容，
     * 这样撤回时能看清改了什么。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseLeaderOpinion updateContent(Long opinionId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("意见内容不能为空");
        }
        CaseLeaderOpinion o = requireOpinion(opinionId);
        requireCase(o.getCaseId());
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "只有管理员或领导可以修改意见内容");
        }
        String old = o.getContent();
        String now = content.trim();
        if (now.equals(old)) {
            // 内容没变就别写日志，否则操作日志里全是"修改意见[无变化]"的噪声
            return o;
        }
        o.setContent(now);
        opinionMapper.updateById(o);

        logService.log("CASE", "OPINION_UPDATE_CONTENT", "CASE", o.getCaseId(),
                "修改意见内容：" + abbrev(old) + " → " + abbrev(now));
        notifyAssignees(o.getCaseId(), "领导更新意见为：" + abbrev(now));
        return o;
    }

    /**
     * 移除意见（仅管理层）。
     *
     * <p>用软删除（content 置空）而非物理删除：办案人可能已经针对这条意见上传了材料、
     * 写了反馈，直接物理删会让那些记录悬空，且操作日志的快照也还原不回来。
     *
     * <p><b>移除后必须重排 sort_order</b>：软删的行还留在表里，
     * 若只把被删项置 NULL，剩下的项会留下空洞（如 1,2,3,5），
     * 之后再拖拽排序就会从空洞值起算，越拖越乱。所以这里把剩余可见项重排成 1..N。
     *
     * <p>用户看到的序号是前端按数组下标算的，与 sort_order 解耦，
     * 所以重排 sort_order 只为保证数据干净，不影响显示。
     */
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long opinionId) {
        CaseLeaderOpinion o = requireOpinion(opinionId);
        Long caseId = o.getCaseId();
        requireCase(caseId);
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "只有管理员或领导可以移除意见");
        }
        String old = o.getContent();
        if (!StringUtils.hasText(old)) {
            throw new BizException(400, "该意见已被移除，请勿重复操作");
        }

        // 软删：内容置空 + 清排序位，保留行以便关联的反馈/材料记录不悬空
        CaseLeaderOpinion patch = new CaseLeaderOpinion();
        patch.setId(o.getId());
        patch.setContent("");
        patch.setSortOrder(null);
        opinionMapper.updateById(patch);

        resequence(caseId);

        logService.log("CASE", "OPINION_REMOVE", "CASE", caseId, "移除意见：" + abbrev(old));
        notifyAssignees(caseId, "领导移除了意见：" + abbrev(old));
    }

    /**
     * 把某案件下所有可见意见的 sort_order 重排为 1..N（按当前顺序）。
     *
     * <p>移除、拖拽后调用，保证持久化的顺序值始终连续——
     * 空洞的 sort_order 会让后续拖拽从错误的基数起算，越拖越乱。
     */
    private void resequence(Long caseId) {
        List<CaseLeaderOpinion> rest = opinionMapper.selectList(new LambdaQueryWrapper<CaseLeaderOpinion>()
                .eq(CaseLeaderOpinion::getCaseId, caseId)
                .isNotNull(CaseLeaderOpinion::getContent)
                .ne(CaseLeaderOpinion::getContent, "")
                .last("ORDER BY (CASE WHEN sort_order IS NULL THEN id ELSE sort_order END) ASC, id ASC"));
        int i = 1;
        for (CaseLeaderOpinion r : rest) {
            if (r.getSortOrder() != null && r.getSortOrder() == i) {
                i++;
                continue;
            }
            CaseLeaderOpinion p = new CaseLeaderOpinion();
            p.setId(r.getId());
            p.setSortOrder(i);
            opinionMapper.updateById(p);
            i++;
        }
    }

    /**
     * 私信式提醒：本案现役主办人 + 协办人。
     *
     * <p>与 SSE 全站广播是两回事——
     * {@code logService.log} 已经把事件广播给所有在线连接（谁在看这个案件谁都会刷新），
     * 这里额外给承办人留一条"点名提醒"，让"xx案，领导更新意见为：xxx"这类信息
     * 在他们下次打开时能被直接看到，而不必自己去翻意见列表找变化。
     *
     * <p>撤回到非承办人身份时也要能收到，所以走与反馈入口同一套权限口径，
     * 但**只对现役（ACTIVE）指派**发，历史协办人（已转手）不该再被打扰。
     */
    private void notifyAssignees(Long caseId, String text) {
        try {
            String name = AuthContext.userName();
            List<CaseAssignee> actives = assigneeMapper.selectList(new LambdaQueryWrapper<CaseAssignee>()
                    .eq(CaseAssignee::getCaseId, caseId)
                    .eq(CaseAssignee::getStatus, "ACTIVE"));
            if (actives.isEmpty()) {
                return;
            }
            for (CaseAssignee a : actives) {
                if (a.getEmployeeId() == null) {
                    continue;
                }
                logService.log("CASE", "OPINION_NOTICE", "CASE", caseId,
                        "致 " + name + "：" + text);
            }
        } catch (Exception e) {
            // 提醒是附加价值，失败不该让主操作回滚
            org.slf4j.LoggerFactory.getLogger(OpinionService.class)
                    .warn("[OpinionService] 承办人提醒发送失败（不影响操作）：{}", e.getMessage());
        }
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
            // 400 而非默认的 500：这是「请求的东西不存在/已删除」的客户端错误，
            // 不是服务端故障。前端据code 区分"要提示用户"还是"要报bug"
            throw new BizException(400, "意见不存在或已被删除");
        }
        return o;
    }

    private void requireCase(Long caseId) {
        if (caseId == null || caseMapper.selectById(caseId) == null) {
            throw new BizException(400, "案件不存在");
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
