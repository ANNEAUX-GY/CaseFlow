package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.dto.TimeNodeSaveRequest;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseTimeNode;
import com.caseflow.entity.SysUser;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CaseTimeNodeMapper;
import com.caseflow.mapper.SysUserMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.DictHolder;
import com.caseflow.support.LogService;
import com.caseflow.vo.TimeNodeVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 案件重要时间节点（2026-10-13）。
 *
 * <p><b>核心设计：多节点是真源，case_info 三列是派生视图。</b>
 *
 * <p>案件列表的「截止期限」、到期提醒、看板逾期统计、到期分桶共约 140 处
 * 引用 {@code case_info.deadline / deadline_label / remind_days}。若把它们
 * 直接废掉改成查本表，所有排序与分桶都得重写，且 SQL 无法用索引排序。
 * 所以做法是：<b>本表存全部节点，每次增删改后把「主节点」派生回写三列</b>，
 * 老逻辑一行不改、行为不变；新功能（多节点管理）看本表。
 *
 * <p><b>主节点怎么定</b>：自动取「最近的未过期节点」，用户不需要手动选。
 * 全部节点都已过期时取最晚的那个（否则逾期案件会突然显示成"无期限"）。
 * 唯一的例外是案件表里手工设过期限但本表为空——那是存量数据，
 * 由 {@code SchemaMigration} 启动时搬成一条节点，之后统一走本表。
 */
@Service
public class TimeNodeService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 合法的预设节点类型（与 DictHolder 的 TIME_NODE 字典同步）。
     *
     * <p>单独列一份是为了能真正做「是否合法」的判断——字典只负责 code→中文名，
     * 查不到时它返回 code 原文，没有「不存在」这个信号。
     */
    private static final java.util.Set<String> NODE_KEYS = new java.util.HashSet<>(
            java.util.Arrays.asList("ACCEPT", "FILING", "DETAIN_END", "ARREST_END",
                    "BAIL_END", "RESIDENCE_END", "SUPP_PROBE", "CUSTODY_CHANGE",
                    "CUSTODY_TERM_CHANGE"));

    @Resource
    private CaseTimeNodeMapper nodeMapper;
    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CaseAssigneeMapper assigneeMapper;
    @Resource
    private SysUserMapper userMapper;
    @Resource
    private CaseSnapshotService snapshotService;
    @Resource
    private LogService logService;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 某案件的节点列表：按日期升序（同日为录入顺序），已过的排在后面更近的之前 */
    public List<TimeNodeVO> listOf(Long caseId) {
        if (caseId == null) {
            return java.util.Collections.emptyList();
        }
        List<CaseTimeNode> list = nodeMapper.selectList(new LambdaQueryWrapper<CaseTimeNode>()
                .eq(CaseTimeNode::getCaseId, caseId)
                .orderByAsc(CaseTimeNode::getNodeTime)
                .orderByAsc(CaseTimeNode::getSort)
                .orderByAsc(CaseTimeNode::getId));
        return list.stream().map(this::toVO).collect(Collectors.toList());
    }

    /**
     * 主节点（列表显示/ 逾期判定用）。本表无记录时回退到案件表自身的期限字段，
     * 这样即使迁移没跑到、只要案件表有期限也照常显示。
     */
    public CaseTimeNode primaryOf(Long caseId) {
        List<CaseTimeNode> list = nodeMapper.selectList(new LambdaQueryWrapper<CaseTimeNode>()
                .eq(CaseTimeNode::getCaseId, caseId));
        if (list.isEmpty()) {
            return null;
        }
        return pickPrimary(list, LocalDateTime.now());
    }

    /**
     * 主节点选取规则（唯一判据，改这里即可）：最近的未过期节点；
     * 全过期时取最晚的那个。
     *
     * <p><b>为什么不让用户手动选主节点</b>：主节点只服务于「列表显示哪个」，
     * 而列表要的是「最要紧的那个」。手动选会出现"用户随手选了个远的，
     * 结果逾期案件不显示逾期"。这个判断交给系统更符合用户预期。
     */
    public static CaseTimeNode pickPrimary(List<CaseTimeNode> list, LocalDateTime now) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        CaseTimeNode best = null;
        CaseTimeNode lastExpired = null;
        for (CaseTimeNode n : list) {
            LocalDateTime t = n.getNodeTime();
            if (t == null) {
                continue;
            }
            if (t.isBefore(now)) {
                if (lastExpired == null || t.isAfter(lastExpired.getNodeTime())) {
                    lastExpired = n;
                }
            } else if (best == null || t.isBefore(best.getNodeTime())) {
                best = n;
            }
        }
        return best != null ? best : lastExpired;
    }

    // ------------------------------------------------------------------
    // 写操作
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public List<TimeNodeVO> add(TimeNodeSaveRequest req) {
        CaseInfo c = requireCase(req.getCaseId());
        checkOperate(c);
        String before = snapshotService.capture(c.getId());
        LocalDateTime now = LocalDateTime.now();

        CaseTimeNode n = new CaseTimeNode();
        n.setCaseId(c.getId());
        applyNode(n, req);
        n.setIsPrimary(0);          // 主节点由 syncPrimary 统一定，不接受前端指定
        n.setSort(nextSort(c.getId()));
        n.setCreatedBy(AuthContext.userId());
        n.setCreatedAt(now);
        n.setUpdatedAt(now);
        nodeMapper.insert(n);

        syncPrimary(c.getId());
        logService.log("CASE", "TIME_NODE_ADD", "CASE", c.getId(),
                "新增时间节点：" + n.getNodeName() + "（" + n.getNodeTime().format(DAY_FMT) + "）",
                before, snapshotService.capture(c.getId()));
        return listOf(c.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public List<TimeNodeVO> update(TimeNodeSaveRequest req) {
        if (req.getId() == null) {
            throw new BizException("缺少时间节点 ID");
        }
        CaseTimeNode old = nodeMapper.selectById(req.getId());
        if (old == null) {
            throw new BizException("时间节点不存在");
        }
        CaseInfo c = requireCase(old.getCaseId());
        checkOperate(c);
        String before = snapshotService.capture(c.getId());
        LocalDateTime now = LocalDateTime.now();

        applyNode(old, req);
        old.setUpdatedAt(now);
        nodeMapper.updateById(old);

        syncPrimary(c.getId());
        logService.log("CASE", "TIME_NODE_UPDATE", "CASE", c.getId(),
                "修改时间节点：" + old.getNodeName(), before, snapshotService.capture(c.getId()));
        return listOf(c.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public List<TimeNodeVO> remove(Long id) {
        CaseTimeNode n = nodeMapper.selectById(id);
        if (n == null) {
            throw new BizException("时间节点不存在");
        }
        Long caseId = n.getCaseId();
        checkOperate(requireCase(caseId));
        String before = snapshotService.capture(caseId);
        nodeMapper.deleteById(id);

        syncPrimary(caseId);
        logService.log("CASE", "TIME_NODE_DELETE", "CASE", caseId,
                "删除时间节点：" + n.getNodeName(), before, snapshotService.capture(caseId));
        return listOf(caseId);
    }

    /**
     * 建案时随案件一并录入节点（不单独打日志——建案本身就是一条 CASE.CREATE 日志）。
     *
     * @return 落库后的节点列表（供建案接口回填）
     */
    public List<TimeNodeVO> addAllQuietly(Long caseId, List<TimeNodeSaveRequest> reqs) {
        if (reqs == null || reqs.isEmpty()) {
            return listOf(caseId);
        }
        LocalDateTime now = LocalDateTime.now();
        int sort = 0;
        for (TimeNodeSaveRequest r : reqs) {
            if (r == null || r.getNodeTime() == null) {
                continue;                // 半行没填日期就跳过，不报错打断建案
            }
            CaseTimeNode n = new CaseTimeNode();
            n.setCaseId(caseId);
            applyNode(n, r);
            n.setIsPrimary(0);
            n.setSort(sort++);
            n.setCreatedBy(AuthContext.userId());
            n.setCreatedAt(now);
            n.setUpdatedAt(now);
            nodeMapper.insert(n);
        }
        syncPrimary(caseId);
        return listOf(caseId);
    }

    /**
     * 把主节点派生回写 {@code case_info} 的三列。
     *
     * <p>这是整个设计的枢纽：**节点表改动 → 主节点 → 案件表三列**，
     * 于是列表、提醒、统计、排序全部自动跟随，无需改它们一行代码。
     *
     * <p>本表为空时**不清空**案件表的三列：存量案件可能只在案件表设过期限
     * 而本表无记录（迁移未执行、或用户手工填的），清空会让期限凭空消失。
     */
    @Transactional(rollbackFor = Exception.class)
    public void syncPrimary(Long caseId) {
        List<CaseTimeNode> list = nodeMapper.selectList(new LambdaQueryWrapper<CaseTimeNode>()
                .eq(CaseTimeNode::getCaseId, caseId));
        CaseTimeNode primary = pickPrimary(list, LocalDateTime.now());

        // 先把 is_primary 归位（至多一个主节点）
        for (CaseTimeNode n : list) {
            int want = (primary != null && primary.getId().equals(n.getId())) ? 1 : 0;
            if (n.getIsPrimary() == null || n.getIsPrimary() != want) {
                n.setIsPrimary(want);
                n.setUpdatedAt(LocalDateTime.now());
                nodeMapper.updateById(n);
            }
        }
        if (list.isEmpty()) {
            return;
        }
        CaseInfo c = caseMapper.selectById(caseId);
        if (c == null) {
            return;
        }
        String label = primary == null ? null : primary.getNodeName();
        LocalDateTime time = primary == null ? null : primary.getNodeTime();
        Integer remind = primary == null ? null
                : (primary.getRemindDays() != null && primary.getRemindDays() > 0 ? primary.getRemindDays() : null);

        // 已经一致就别写：避免每次改节点都把案件表的 updated_at 顶起来，
        // 那会让案件列表按更新时间排序时无谓跳动
        if (eq(c.getDeadline(), time) && eq(c.getDeadlineLabel(), label) && eq(c.getRemindDays(), remind)) {
            return;
        }
        c.setDeadline(time);
        c.setDeadlineLabel(label);
        c.setRemindDays(remind);
        c.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(c);
    }

    /** 把主节点信息补进 VO（案件详情/列表展示用） */
    public TimeNodeVO primaryVO(Long caseId) {
        CaseTimeNode n = primaryOf(caseId);
        return n == null ? null : toVO(n);
    }

    // ------------------------------------------------------------------

    private void applyNode(CaseTimeNode n, TimeNodeSaveRequest req) {
        if (req.getNodeTime() == null) {
            throw new BizException("请选择节点日期");
        }
        String key = StringUtils.hasText(req.getNodeKey()) ? req.getNodeKey().trim().toUpperCase() : null;
        // OTHER 视为「自定义」：它没有专属展示名，走用户填的名称
        if ("OTHER".equals(key)) {
            key = null;
        }
        // 预设类型必须真的在枚举里，防止前端传脏值进库。
        // 注意：不能用 `DictHolder.name(...) == null` 判断——它对未知 code
        // 会**返回 code 原文**而不是 null，那样校验永远不成立，脏值会静默入库
        // （node_name 还会变成"BOGUS"，界面上直接显示乱码）。
        if (key != null && !NODE_KEYS.contains(key)) {
            throw new BizException("未知的节点类型：" + req.getNodeKey());
        }
        String name = StringUtils.hasText(req.getNodeName()) ? req.getNodeName().trim() : null;
        if (key != null) {
            name = DictHolder.name("TIME_NODE", key);        // 预设类型统一用标准名，避免用户改写成别名
        } else if (!StringUtils.hasText(name)) {
            throw new BizException("请填写节点名称，或选择一个预设节点类型");
        }
        n.setNodeKey(key);
        n.setNodeName(name.length() > 64 ? name.substring(0, 64) : name);
        n.setNodeTime(normalizeTime(req.getNodeTime()));
        n.setRemindDays(req.getRemindDays() != null && req.getRemindDays() > 0 ? req.getRemindDays() : null);
        if (req.getRemark() != null) {
            n.setRemark(req.getRemark().trim());
        }
    }

    /**
     * 按天选的日期统一归一到当天 23:59:59。
     *
     * <p>与 {@code CaseService#normalizeDeadline} 同一口径——若一个节点归到
     * 00:00:00、另一个归到 23:59:59，当天到期的节点会在列表上"提前一天变红"。
     */
    private LocalDateTime normalizeTime(LocalDateTime t) {
        if (t.getHour() == 0 && t.getMinute() == 0 && t.getSecond() == 0) {
            return t.toLocalDate().atTime(23, 59, 59);
        }
        return t;
    }

    /** 新节点的排序值：接在现有节点之后（用户录入顺序即列表默认顺序） */
    private int nextSort(Long caseId) {
        Long cnt = nodeMapper.selectCount(new LambdaQueryWrapper<CaseTimeNode>()
                .eq(CaseTimeNode::getCaseId, caseId));
        return cnt == null ? 0 : cnt.intValue();
    }

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

    /**
     * 写操作权限：管理层 或 本案现职承办人。
     *
     * <p>与 {@code SuspectService.checkOperate} 同一口径：嫌疑人/节点都属于
     * 办案信息，普通民警不该能改别人的案。
     */
    private void checkOperate(CaseInfo c) {
        if (AuthContext.isFullAccess()) {
            return;
        }
        Long empId = AuthContext.get() == null ? null : AuthContext.get().getEmployeeId();
        if (empId == null) {
            throw new BizException(403, "只有案件承办人或管理层可以维护时间节点");
        }
        Long cnt = assigneeMapper.selectCount(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getCaseId, c.getId())
                .eq(CaseAssignee::getEmployeeId, empId)
                .eq(CaseAssignee::getStatus, "ACTIVE"));
        if (cnt == null || cnt == 0) {
            throw new BizException(403, "只有案件承办人或管理层可以维护时间节点");
        }
    }

    private TimeNodeVO toVO(CaseTimeNode n) {
        LocalDateTime now = LocalDateTime.now();
        TimeNodeVO vo = new TimeNodeVO();
        vo.setId(n.getId());
        vo.setCaseId(n.getCaseId());
        vo.setNodeKey(n.getNodeKey());
        vo.setNodeKeyName(DictHolder.name("TIME_NODE", n.getNodeKey()));
        vo.setNodeName(n.getNodeName());
        vo.setNodeTime(n.getNodeTime());
        vo.setNodeTimeText(n.getNodeTime() == null ? null : n.getNodeTime().format(DAY_FMT));
        vo.setDaysLeft(DictHolder.daysLeft(n.getNodeTime(), now));
        vo.setRemindDays(n.getRemindDays());
        vo.setIsPrimary(n.getIsPrimary());
        vo.setSort(n.getSort());
        vo.setRemark(n.getRemark());
        if (n.getCreatedBy() != null) {
            SysUser u = userMapper.selectById(n.getCreatedBy());
            vo.setCreatedByName(u == null ? null : u.getDisplayName());
        }
        vo.setCreatedAt(n.getCreatedAt());
        vo.setUpdatedAt(n.getUpdatedAt());
        return vo;
    }

    /** null 安全的字符串比较 */
    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** null 安全的 LocalDateTime 比较 */
    private static boolean eq(LocalDateTime a, LocalDateTime b) {
        return a == null ? b == null : a.isEqual(b);
    }

    /** null 安全的 Integer 比较 */
    private static boolean eq(Integer a, Integer b) {
        return a == null ? b == null : a.equals(b);
    }
}
