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
        List<CaseLeaderOpinion> list = opinionMapper.selectList(new LambdaQueryWrapper<CaseLeaderOpinion>()
                .eq(CaseLeaderOpinion::getCaseId, caseId)
                // 升序：序号自上而下递增（1 起连续）。
                // 排序键用 sort_order，NULL（旧数据）回退按 id 升序 —— H2/MySQL 都支持
                // COALESCE，且它作用在已有列上，不需要额外迁移，索引照旧命中 case_id。
                .apply("CASE WHEN sort_order IS NULL THEN id ELSE sort_order END ASC, id ASC"));
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
        // 只能排本案的意见，防止跨案件篡改
        List<CaseLeaderOpinion> own = opinionMapper.selectList(new LambdaQueryWrapper<CaseLeaderOpinion>()
                .eq(CaseLeaderOpinion::getCaseId, caseId));
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
            if (!o.getSortOrder().equals(pos)) {
                o.setSortOrder(pos);
                opinionMapper.updateById(o);
            }
        }
        // 提交列表没带上的意见（理论上不会）排到末尾，保证 1..N 连续
        if (pos < own.size()) {
            for (CaseLeaderOpinion o : own) {
                if (!seen.contains(o.getId())) {
                    pos++;
                    o.setSortOrder(pos);
                    opinionMapper.updateById(o);
                }
            }
        }

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
