package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.caseflow.common.PageResult;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.OperationLog;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.OperationLogMapper;
import com.caseflow.support.CaseSnapshot;
import com.caseflow.support.LogService;
import com.caseflow.vo.LogVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 操作日志的查询与撤回。
 *
 * <p>撤回规则（宁可少撤，不可撤错）：
 * <ol>
 *   <li>只有案件类写操作（新建 / 修改 / 指派 / 状态 / 删除，以及撤回本身）可撤回；</li>
 *   <li>已被撤回的不能再撤；</li>
 *   <li>必须是**该案件上最新的一条**——否则撤回会覆盖掉它之后的改动；</li>
 *   <li>撤回本身也记一条日志，所以撤回之后还能再撤回（等价于重做）。</li>
 * </ol>
 */
@Service
public class OperationLogService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 可撤回的动作白名单 */
    private static final Set<String> UNDOABLE = new LinkedHashSet<>(Arrays.asList(
            "CREATE", "UPDATE", "ASSIGN", "STATUS", "DELETE", "UNDO",
            // 嫌疑人增删改：快照里带嫌疑人列表，撤回即整体还原
            "SUSPECT_ADD", "SUSPECT_UPDATE", "SUSPECT_DELETE",
            // 侦查计划增删改/完成：快照里带计划列表，撤回即整体还原
            "PLAN_ADD", "PLAN_UPDATE", "PLAN_DONE", "PLAN_CANCEL",
            // 侦查进度流转 / 强制措施登记：快照带案件新字段，撤回即回滚
            "INVESTIGATION", "MEASURE"));

    @Resource
    private OperationLogMapper logMapper;
    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CaseSnapshotService snapshotService;
    @Resource
    private LogService logService;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public PageResult<LogVO> page(Integer page, Integer size, String module) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        LambdaQueryWrapper<OperationLog> q = new LambdaQueryWrapper<>();
        if (module != null && !module.trim().isEmpty()) {
            q.eq(OperationLog::getModule, module.trim().toUpperCase());
        }
        q.orderByDesc(OperationLog::getId);
        IPage<OperationLog> result = logMapper.selectPage(new Page<>(p, s), q);

        List<OperationLog> records = result.getRecords();
        Map<Long, Long> latestIds = latestUndoableIds(targetsOf(records));
        Map<Long, CaseInfo> caseMap = casesOf(records);
        List<LogVO> list = new ArrayList<>();
        for (OperationLog l : records) {
            list.add(toVO(l, latestIds, caseMap, false));
        }
        return new PageResult<>(list, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 最近 N 条（工作台「最近操作」面板用） */
    public List<LogVO> recent(int limit) {
        int n = limit < 1 ? 8 : Math.min(limit, 100);
        LambdaQueryWrapper<OperationLog> q = new LambdaQueryWrapper<OperationLog>()
                .orderByDesc(OperationLog::getId)
                .last("limit " + n);
        List<OperationLog> records = logMapper.selectList(q);
        Map<Long, Long> latestIds = latestUndoableIds(targetsOf(records));
        Map<Long, CaseInfo> caseMap = casesOf(records);
        List<LogVO> list = new ArrayList<>();
        for (OperationLog l : records) {
            list.add(toVO(l, latestIds, caseMap, false));
        }
        return list;
    }

    public LogVO detail(Long id) {
        OperationLog log = logMapper.selectById(id);
        if (log == null) {
            throw new BizException("操作记录不存在");
        }
        Set<Long> targets = new HashSet<>();
        if (log.getTargetId() != null) {
            targets.add(log.getTargetId());
        }
        Map<Long, Long> latestIds = latestUndoableIds(targets);
        Map<Long, CaseInfo> caseMap = casesOf(Collections.singletonList(log));
        LogVO vo = toVO(log, latestIds, caseMap, true);
        if (log.getUndoOf() != null) {
            OperationLog origin = logMapper.selectById(log.getUndoOf());
            vo.setUndoOfContent(origin == null ? null : origin.getContent());
        }
        return vo;
    }

    /** 单个案件的办理进度（详情抽屉时间线用）：按时间正序排列全部案件操作 */
    public List<LogVO> caseLogs(Long caseId) {
        List<OperationLog> records = logMapper.selectList(new LambdaQueryWrapper<OperationLog>()
                .eq(OperationLog::getModule, "CASE")
                .eq(OperationLog::getTargetId, caseId)
                .orderByAsc(OperationLog::getId));
        Map<Long, CaseInfo> caseMap = casesOf(records);
        List<LogVO> list = new ArrayList<>();
        for (OperationLog l : records) {
            // 进度时间线不需要「可否撤回」信息，latestIds 传空即可
            list.add(toVO(l, Collections.emptyMap(), caseMap, false));
        }
        return list;
    }

    // ------------------------------------------------------------------
    // 撤回
    // ------------------------------------------------------------------

    /** 撤回指定操作：把它的 snapshot_before 原样写回，并记录一条 action=UNDO 的日志 */
    @Transactional(rollbackFor = Exception.class)
    public LogVO undo(Long logId) {
        OperationLog log = logMapper.selectById(logId);
        if (log == null) {
            throw new BizException("操作记录不存在");
        }
        String reason = blockReason(log);
        if (reason != null) {
            throw new BizException(reason);
        }
        // 当前状态 = 撤回操作的「操作前」；目标状态 = 原操作的「操作前」
        String current = snapshotService.capture(log.getTargetId());
        String target = log.getSnapshotBefore();
        snapshotService.restore(target, log.getTargetId());
        OperationLog undoLog = logService.log("CASE", "UNDO", "CASE", log.getTargetId(),
                "撤回操作：" + abbreviate(log.getContent(), 80), current, target, log.getId());
        logService.markUndone(log.getId(), undoLog.getId());
        return detail(undoLog.getId());
    }

    /** 撤回上一步：全局最新的一条可撤回操作（对应 Ctrl+Z） */
    @Transactional(rollbackFor = Exception.class)
    public LogVO undoLatest() {
        OperationLog latest = logMapper.selectList(new LambdaQueryWrapper<OperationLog>()
                        .eq(OperationLog::getModule, "CASE")
                        .eq(OperationLog::getTargetType, "CASE")
                        .eq(OperationLog::getUndone, 0)
                        .in(OperationLog::getAction, UNDOABLE)
                        .orderByDesc(OperationLog::getId)
                        .last("limit 1"))
                .stream().findFirst().orElse(null);
        if (latest == null) {
            throw new BizException("暂无可撤回的操作");
        }
        return undo(latest.getId());
    }

    /** 不可撤回的原因；返回 null 表示可以撤回 */
    private String blockReason(OperationLog log) {
        if (log.getUndone() != null && log.getUndone() == 1) {
            return "该操作已经被撤回过了";
        }
        if (!"CASE".equals(log.getModule()) || !"CASE".equals(log.getTargetType())) {
            return "只有案件操作可以撤回（登录、上传附件这类不支持）";
        }
        if (log.getTargetId() == null) {
            return "该操作没有关联的案件，无法撤回";
        }
        if (!UNDOABLE.contains(log.getAction())) {
            return "「" + actionName(log.getModule(), log.getAction()) + "」不支持撤回";
        }
        if (log.getSnapshotBefore() == null) {
            return "该操作早于「可撤回」功能上线，没有留下状态快照";
        }
        if (!log.getId().equals(latestUndoableIds(Collections.singletonList(log.getTargetId()))
                .get(log.getTargetId()))) {
            return "该案件之后又有新操作，请先撤回更新的那一步";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 组装
    // ------------------------------------------------------------------

    private LogVO toVO(OperationLog l, Map<Long, Long> latestIds, Map<Long, CaseInfo> caseMap, boolean withChanges) {
        LogVO v = new LogVO();
        v.setId(l.getId());
        v.setModule(l.getModule());
        v.setAction(l.getAction());
        v.setActionName(actionName(l.getModule(), l.getAction()));
        v.setTargetType(l.getTargetType());
        v.setTargetId(l.getTargetId());
        v.setContent(l.getContent());
        v.setOperatorName(l.getOperatorName());
        v.setCreatedAt(l.getCreatedAt());
        v.setCreatedAtText(l.getCreatedAt() == null ? null : l.getCreatedAt().format(TS));
        v.setUndone(l.getUndone() != null && l.getUndone() == 1);
        v.setUndoLogId(l.getUndoLogId());
        v.setUndoOf(l.getUndoOf());

        String reason = blockReason(l, latestIds);
        v.setUndoable(reason == null);
        v.setUndoHint(reason);

        fillCaseInfo(v, l, caseMap);
        if (withChanges) {
            v.setChanges(snapshotService.diff(l.getSnapshotBefore(), l.getSnapshotAfter()));
        }
        return v;
    }

    /** 列表场景复用已算好的 latestIds，避免每条日志都查一次库 */
    private String blockReason(OperationLog log, Map<Long, Long> latestIds) {
        if (log.getUndone() != null && log.getUndone() == 1) {
            return "已被撤回";
        }
        if (!"CASE".equals(log.getModule()) || !"CASE".equals(log.getTargetType())
                || log.getTargetId() == null || !UNDOABLE.contains(log.getAction())) {
            return "不支持撤回";
        }
        if (log.getSnapshotBefore() == null) {
            return "无状态快照";
        }
        if (!log.getId().equals(latestIds.get(log.getTargetId()))) {
            return "之后有新操作";
        }
        return null;
    }

    /** 案件编号 / 名称：案件已删除时从快照里取，保证历史日志仍能说清「是哪件事」 */
    private void fillCaseInfo(LogVO v, OperationLog l, Map<Long, CaseInfo> caseMap) {
        if (l.getTargetId() == null || !"CASE".equals(l.getTargetType())) {
            return;
        }
        CaseInfo c = caseMap.get(l.getTargetId());
        if (c != null) {
            v.setCaseNo(c.getCaseNo());
            v.setCaseName(c.getName());
            v.setCaseExists(true);
            return;
        }
        v.setCaseExists(false);
        String[] pools = {l.getSnapshotAfter(), l.getSnapshotBefore()};
        for (String json : pools) {
            CaseSnapshot s = snapshotService.read(json);
            if (s != null && s.getInfo() != null) {
                v.setCaseNo(s.getInfo().getCaseNo());
                v.setCaseName(s.getInfo().getName());
                return;
            }
        }
    }

    /**
     * 每个案件上「最新的那条可撤回日志」的 id。
     * 批量算一次，避免列表里逐条查库。
     */
    private Map<Long, Long> latestUndoableIds(Collection<Long> targetIds) {
        Map<Long, Long> map = new HashMap<>();
        if (targetIds == null || targetIds.isEmpty()) {
            return map;
        }
        List<OperationLog> list = logMapper.selectList(new LambdaQueryWrapper<OperationLog>()
                .eq(OperationLog::getModule, "CASE")
                .eq(OperationLog::getTargetType, "CASE")
                .eq(OperationLog::getUndone, 0)
                .in(OperationLog::getAction, UNDOABLE)
                .in(OperationLog::getTargetId, targetIds)
                .select(OperationLog::getId, OperationLog::getTargetId));
        for (OperationLog l : list) {
            Long cur = map.get(l.getTargetId());
            if (cur == null || l.getId() > cur) {
                map.put(l.getTargetId(), l.getId());
            }
        }
        return map;
    }

    private Set<Long> targetsOf(List<OperationLog> logs) {
        return logs.stream()
                .filter(l -> "CASE".equals(l.getTargetType()) && l.getTargetId() != null)
                .map(OperationLog::getTargetId)
                .collect(Collectors.toSet());
    }

    private Map<Long, CaseInfo> casesOf(List<OperationLog> logs) {
        Set<Long> ids = targetsOf(logs);
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, CaseInfo> map = new HashMap<>();
        for (CaseInfo c : caseMapper.selectBatchIds(ids)) {
            map.put(c.getId(), c);
        }
        return map;
    }

    public static String actionName(String module, String action) {
        if (module == null || action == null) {
            return action;
        }
        switch (module.toUpperCase() + "." + action.toUpperCase()) {
            case "CASE.CREATE":   return "新建案件";
            case "CASE.UPDATE":   return "修改案件";
            case "CASE.ASSIGN":   return "指派 / 改派";
            case "CASE.STATUS":   return "状态变更";
            case "CASE.DELETE":   return "删除案件";
            case "CASE.UNDO":     return "撤回操作";
            case "CASE.SUSPECT_ADD":    return "新增嫌疑人";
            case "CASE.SUSPECT_UPDATE": return "修改嫌疑人";
            case "CASE.SUSPECT_DELETE": return "删除嫌疑人";
            case "CASE.PLAN_ADD":    return "新增侦查计划";
            case "CASE.PLAN_UPDATE": return "修改侦查计划";
            case "CASE.PLAN_DONE":   return "完成侦查计划";
            case "CASE.PLAN_CANCEL": return "取消侦查计划";
            case "CASE.INVESTIGATION": return "侦查进度流转";
            case "CASE.MEASURE":     return "强制措施登记";
            // 批注 / 领导意见：旁注类操作，不进快照，故不在 UNDOABLE 白名单（不可撤回）
            case "CASE.COMMENT_ADD":       return "进度批注";
            case "CASE.COMMENT_UPDATE":    return "编辑批注";
            case "CASE.COMMENT_DELETE":    return "删除批注";
            case "CASE.OPINION_ADD":       return "提出意见";
            case "CASE.OPINION_FEEDBACK":  return "意见反馈";
            case "FILE.UPLOAD":   return "上传附件";
            case "FILE.DELETE":   return "删除附件";
            case "EMPLOYEE.CREATE": return "新增员工";
            case "EMPLOYEE.UPDATE": return "修改员工";
            case "EMPLOYEE.DELETE": return "删除员工";
            case "EMPLOYEE.IMPORT": return "导入员工图谱";
            case "AUTH.LOGIN":    return "登录";
            case "AUTH.LOGOUT":   return "退出登录";
            default:              return action;
        }
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
