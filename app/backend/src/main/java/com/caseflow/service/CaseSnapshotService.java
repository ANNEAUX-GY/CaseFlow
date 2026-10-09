package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseFile;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CasePlan;
import com.caseflow.entity.CaseSuspect;
import com.caseflow.entity.CaseTodo;
import com.caseflow.entity.OrgEmployee;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseFileMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CasePlanMapper;
import com.caseflow.mapper.CaseSuspectMapper;
import com.caseflow.mapper.CaseTodoMapper;
import com.caseflow.support.CaseSnapshot;
import com.caseflow.support.DictHolder;
import com.caseflow.vo.ChangeVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 案件快照：抓取 / 回写 / 差异比对。
 *
 * <p>撤回功能的核心。每个写操作前后各存一份完整快照，
 * 「撤回」就是把 {@code snapshot_before} 原样写回去——包括案件字段、指派关系、附件归属，
 * 以及「案件当时根本不存在」这种情况。
 */
@Slf4j
@Service
public class CaseSnapshotService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CaseAssigneeMapper assigneeMapper;
    @Resource
    private CaseFileMapper fileMapper;
    @Resource
    private CaseSuspectMapper suspectMapper;
    @Resource
    private CasePlanMapper planMapper;
    @Resource
    private CaseTodoMapper todoMapper;
    @Resource
    private EmployeeService employeeService;
    @Resource
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // 抓取
    // ------------------------------------------------------------------

    /** 「案件不存在」的快照：新建操作的 before、删除操作的 after 都是它 */
    public String absentJson() {
        CaseSnapshot s = new CaseSnapshot();
        s.setExists(false);
        return toJson(s);
    }

    /** 抓取某案件当前完整状态；案件不存在时返回「不存在」快照 */
    public String capture(Long caseId) {
        if (caseId == null) {
            return absentJson();
        }
        CaseInfo c = caseMapper.selectById(caseId);
        if (c == null) {
            return absentJson();
        }
        CaseSnapshot s = new CaseSnapshot();
        s.setExists(true);
        s.setInfo(c);
        s.setAssignees(assigneeMapper.selectList(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getCaseId, caseId).orderByAsc(CaseAssignee::getId)));
        s.setFiles(fileMapper.selectList(new LambdaQueryWrapper<CaseFile>()
                .eq(CaseFile::getCaseId, caseId).orderByAsc(CaseFile::getId)));
        s.setSuspects(suspectMapper.selectList(new LambdaQueryWrapper<CaseSuspect>()
                .eq(CaseSuspect::getCaseId, caseId).orderByAsc(CaseSuspect::getId)));
        s.setPlans(planMapper.selectList(new LambdaQueryWrapper<CasePlan>()
                .eq(CasePlan::getCaseId, caseId).orderByAsc(CasePlan::getId)));
        s.setTodos(todoMapper.selectList(new LambdaQueryWrapper<CaseTodo>()
                .eq(CaseTodo::getCaseId, caseId).orderByAsc(CaseTodo::getId)));
        return toJson(s);
    }

    // ------------------------------------------------------------------
    // 回写
    // ------------------------------------------------------------------

    /**
     * 把快照原样写回数据库。空快照视为「无操作」。
     *
     * @param fallbackCaseId 快照本身不带案件 ID（=「当时不存在」）时的兜底 ID，
     *                       由操作日志的 target_id 提供；撤回「新建」就靠它把案件删掉。
     */
    @Transactional(rollbackFor = Exception.class)
    public void restore(String json, Long fallbackCaseId) {
        CaseSnapshot snap = read(json);
        if (snap == null) {
            return;
        }
        Long caseId = caseIdOf(snap);
        if (caseId == null) {
            caseId = fallbackCaseId;
        }
        if (caseId == null) {
            throw new BizException("快照里没有案件信息，无法回滚");
        }
        if (!Boolean.TRUE.equals(snap.getExists())) {
            // 回到「不存在」：撤掉案件本体与它的全部指派关系、嫌疑人、侦查计划
            // （附件表不动，与 CaseService.delete 的行为保持一致）
            assigneeMapper.delete(new LambdaQueryWrapper<CaseAssignee>().eq(CaseAssignee::getCaseId, caseId));
            suspectMapper.delete(new LambdaQueryWrapper<CaseSuspect>().eq(CaseSuspect::getCaseId, caseId));
            planMapper.delete(new LambdaQueryWrapper<CasePlan>().eq(CasePlan::getCaseId, caseId));
            todoMapper.delete(new LambdaQueryWrapper<CaseTodo>().eq(CaseTodo::getCaseId, caseId));
            caseMapper.deleteById(caseId);
            return;
        }
        CaseInfo info = snap.getInfo();
        if (info == null) {
            throw new BizException("快照数据不完整，无法回滚");
        }
        info.setId(caseId);
        CaseInfo current = caseMapper.selectById(caseId);
        Long currentSourceFileId = current == null ? null : current.getSourceFileId();
        if (current == null) {
            caseMapper.insertWithId(info);
        } else {
            if (!Objects.equals(current.getCaseNo(), info.getCaseNo())) {
                // id 被别的案件占用了，宁可报错也不覆盖别人的数据
                throw new BizException("案件 ID 已被其它案件占用，无法还原：" + caseId);
            }
            caseMapper.update(null, fullUpdate(info, caseId));
        }
        restoreAssignees(caseId, snap.getAssignees());
        restoreSuspects(caseId, snap.getSuspects());
        restorePlans(caseId, snap.getPlans());
        restoreTodos(caseId, snap.getTodos());
        alignSourceFile(caseId, info.getSourceFileId(), currentSourceFileId);
    }

    /** 全字段显式 set：快照里的 null 也必须写回去（MyBatis-Plus updateById 会跳过 null） */
    private LambdaUpdateWrapper<CaseInfo> fullUpdate(CaseInfo i, Long caseId) {
        return new LambdaUpdateWrapper<CaseInfo>()
                .eq(CaseInfo::getId, caseId)
                .set(CaseInfo::getCaseNo, i.getCaseNo())
                .set(CaseInfo::getName, i.getName())
                .set(CaseInfo::getSourceType, i.getSourceType())
                .set(CaseInfo::getSourceFileId, i.getSourceFileId())
                .set(CaseInfo::getCaseType, i.getCaseType())
                .set(CaseInfo::getCategory, i.getCategory())
                .set(CaseInfo::getFilingNo, i.getFilingNo())
                .set(CaseInfo::getMediationNo, i.getMediationNo())
                .set(CaseInfo::getCaseMeasure, i.getCaseMeasure())
                .set(CaseInfo::getMeasureDate, i.getMeasureDate())
                .set(CaseInfo::getDetainDeadline, i.getDetainDeadline())
                .set(CaseInfo::getInvestigationStatus, i.getInvestigationStatus())
                .set(CaseInfo::getDescription, i.getDescription())
                .set(CaseInfo::getPriority, i.getPriority())
                .set(CaseInfo::getDeadline, i.getDeadline())
                // 期限节点 / 提前提醒 / 重点关注（2026-10-09）：
                // 老快照里没有这三列，focus 必须兜成 0（该列 NOT NULL，写 null 会插不进去）
                .set(CaseInfo::getDeadlineLabel, i.getDeadlineLabel())
                .set(CaseInfo::getRemindDays, i.getRemindDays())
                .set(CaseInfo::getFocus, i.getFocus() == null ? 0 : i.getFocus())
                .set(CaseInfo::getStatus, i.getStatus())
                .set(CaseInfo::getRemark, i.getRemark())
                .set(CaseInfo::getCreatedBy, i.getCreatedBy())
                .set(CaseInfo::getCreatedAt, i.getCreatedAt())
                .set(CaseInfo::getUpdatedAt, LocalDateTime.now());
    }

    /**
     * 指派关系按快照整体替换：先清空本案关系，再按快照逐行写回（含原有主键）。
     * 这样被改派置为 REPLACED 的历史行也能一并还原，且不会和现状串味。
     */
    private void restoreAssignees(Long caseId, List<CaseAssignee> snap) {
        assigneeMapper.delete(new LambdaQueryWrapper<CaseAssignee>().eq(CaseAssignee::getCaseId, caseId));
        if (snap == null || snap.isEmpty()) {
            return;
        }
        for (CaseAssignee a : snap) {
            a.setCaseId(caseId);
            if (a.getId() == null) {
                assigneeMapper.insert(a);
            } else {
                assigneeMapper.insertWithId(a);
            }
        }
    }

    /**
     * 嫌疑人按快照整体替换：先清空本案嫌疑人，再按快照逐行写回（含原有主键）。
     * 与指派关系同款处理——撤回「新增/删除嫌疑人」时才是精确还原。
     */
    private void restoreSuspects(Long caseId, List<CaseSuspect> snap) {
        suspectMapper.delete(new LambdaQueryWrapper<CaseSuspect>().eq(CaseSuspect::getCaseId, caseId));
        if (snap == null || snap.isEmpty()) {
            return;
        }
        for (CaseSuspect s : snap) {
            s.setCaseId(caseId);
            if (s.getId() == null) {
                suspectMapper.insert(s);
            } else {
                suspectMapper.insertWithId(s);
            }
        }
    }

    /**
     * 侦查计划按快照整体替换：先清空本案计划，再按快照逐行写回（含原有主键）。
     * 与嫌疑人同款处理——撤回「新增/完成/取消计划」时才是精确还原。
     */
    private void restorePlans(Long caseId, List<CasePlan> snap) {
        planMapper.delete(new LambdaQueryWrapper<CasePlan>().eq(CasePlan::getCaseId, caseId));
        if (snap == null || snap.isEmpty()) {
            return;
        }
        for (CasePlan p : snap) {
            p.setCaseId(caseId);
            if (p.getId() == null) {
                planMapper.insert(p);
            } else {
                planMapper.insertWithId(p);
            }
        }
    }

    /**
     * 待办按快照整体替换：先清空本案待办，再按快照逐行写回（含原有主键）。
     * 佐证材料本身是 case_file，不在这里动——撤回不会删掉已上传的材料（与附件的既有约定一致）。
     */
    private void restoreTodos(Long caseId, List<CaseTodo> snap) {
        todoMapper.delete(new LambdaQueryWrapper<CaseTodo>().eq(CaseTodo::getCaseId, caseId));
        if (snap == null || snap.isEmpty()) {
            return;
        }
        for (CaseTodo t : snap) {
            t.setCaseId(caseId);
            if (t.getId() == null) {
                todoMapper.insert(t);
            } else {
                todoMapper.insertWithId(t);
            }
        }
    }

    /**
     * 只对齐「来源附件」这一处绑定。
     *
     * <p>案件操作里唯一会改到 case_file 的地方，是「上传材料并作为案件来源」，
     * 它会把该附件的 case_id 指向本案。撤回时把这次绑定还原即可——
     * **不能**按快照里的附件列表整体替换 case_file，否则会把用户后来正常补充的材料一并解绑。
     *
     * @param want    快照里案件当时指向的来源附件
     * @param current 撤回前案件指向的来源附件
     */
    private void alignSourceFile(Long caseId, Long want, Long current) {
        if (current != null && !current.equals(want)) {
            CaseFile f = fileMapper.selectById(current);
            if (f != null && caseId.equals(f.getCaseId())) {
                fileMapper.update(null, new LambdaUpdateWrapper<CaseFile>()
                        .eq(CaseFile::getId, current)
                        .set(CaseFile::getCaseId, null));
            }
        }
        if (want != null) {
            CaseFile f = fileMapper.selectById(want);
            if (f != null && !caseId.equals(f.getCaseId())) {
                fileMapper.update(null, new LambdaUpdateWrapper<CaseFile>()
                        .eq(CaseFile::getId, want)
                        .set(CaseFile::getCaseId, caseId));
            }
        }
    }

    // ------------------------------------------------------------------
    // 差异比对（给前端渲染「变更前 / 变更后」）
    // ------------------------------------------------------------------

    public List<ChangeVO> diff(String beforeJson, String afterJson) {
        List<ChangeVO> out = new ArrayList<>();
        CaseSnapshot b = read(beforeJson);
        CaseSnapshot a = read(afterJson);
        boolean bExist = b != null && Boolean.TRUE.equals(b.getExists());
        boolean aExist = a != null && Boolean.TRUE.equals(a.getExists());
        if (!bExist && !aExist) {
            return out;
        }
        if (!bExist) {
            fillNewCase(out, a);
            return out;
        }
        if (!aExist) {
            fillRemovedCase(out, b);
            return out;
        }
        CaseInfo bi = b.getInfo();
        CaseInfo ai = a.getInfo();
        if (bi == null || ai == null) {
            return out;
        }
        compare(out, "name", "案件名称", bi.getName(), ai.getName());
        compare(out, "caseType", "案卷类型", dict("CASE_TYPE", bi.getCaseType()), dict("CASE_TYPE", ai.getCaseType()));
        compare(out, "category", "案件类别", bi.getCategory(), ai.getCategory());
        compare(out, "filingNo", "案件编号", bi.getFilingNo(), ai.getFilingNo());
        compare(out, "mediationNo", "调解书", bi.getMediationNo(), ai.getMediationNo());
        compare(out, "description", "备注说明", bi.getDescription(), ai.getDescription());
        compare(out, "priority", "优先级", dict("PRIORITY", bi.getPriority()), dict("PRIORITY", ai.getPriority()));
        compare(out, "deadline", "截止期限", fmt(bi.getDeadline()), fmt(ai.getDeadline()));
        // 期限节点名称 / 提前提醒 / 重点关注（2026-10-09）
        compare(out, "deadlineLabel", "期限节点", bi.getDeadlineLabel(), ai.getDeadlineLabel());
        compare(out, "remindDays", "提前提醒",
                remindText(bi.getRemindDays()), remindText(ai.getRemindDays()));
        compare(out, "focus", "重点关注",
                focusText(bi.getFocus()), focusText(ai.getFocus()));
        compare(out, "status", "状态", dict("STATUS", bi.getStatus()), dict("STATUS", ai.getStatus()));
        compare(out, "caseMeasure", "强制措施", dict("CASE_MEASURE", bi.getCaseMeasure()), dict("CASE_MEASURE", ai.getCaseMeasure()));
        compare(out, "investigationStatus", "侦查进度", dict("INVESTIGATION_STATUS", bi.getInvestigationStatus()), dict("INVESTIGATION_STATUS", ai.getInvestigationStatus()));
        compare(out, "detainDeadline", "措施期限", fmt(bi.getDetainDeadline()), fmt(ai.getDetainDeadline()));
        compare(out, "remark", "归档备注", bi.getRemark(), ai.getRemark());
        compare(out, "sourceType", "来源", dict("SOURCE_TYPE", bi.getSourceType()), dict("SOURCE_TYPE", ai.getSourceType()));
        compare(out, "owner", "主办人", names(b, "OWNER"), names(a, "OWNER"));
        compare(out, "members", "协办人", names(b, "MEMBER"), names(a, "MEMBER"));
        compare(out, "suspects", "嫌疑人", suspectNames(b), suspectNames(a));
        compare(out, "files", "附件", fileNames(b), fileNames(a));
        return out;
    }

    private void fillNewCase(List<ChangeVO> out, CaseSnapshot a) {
        CaseInfo i = a.getInfo();
        if (i == null) {
            return;
        }
        add(out, "name", "案件名称", null, i.getName(), "ADD");
        add(out, "caseNo", "案件编号", null, i.getCaseNo(), "ADD");
        add(out, "caseType", "案卷类型", null, dict("CASE_TYPE", i.getCaseType()), "ADD");
        add(out, "category", "案件类别", null, i.getCategory(), "ADD");
        add(out, "filingNo", "案件编号", null, i.getFilingNo(), "ADD");
        add(out, "mediationNo", "调解书", null, i.getMediationNo(), "ADD");
        add(out, "deadlineLabel", "期限节点", null, i.getDeadlineLabel(), "ADD");
        add(out, "remindDays", "提前提醒", null, remindText(i.getRemindDays()), "ADD");
        add(out, "priority", "优先级", null, dict("PRIORITY", i.getPriority()), "ADD");
        add(out, "deadline", "截止期限", null, fmt(i.getDeadline()), "ADD");
        add(out, "status", "状态", null, dict("STATUS", i.getStatus()), "ADD");
        add(out, "sourceType", "来源", null, dict("SOURCE_TYPE", i.getSourceType()), "ADD");
        add(out, "owner", "主办人", null, names(a, "OWNER"), "ADD");
        add(out, "members", "协办人", null, names(a, "MEMBER"), "ADD");
        add(out, "files", "附件", null, fileNames(a), "ADD");
    }

    private void fillRemovedCase(List<ChangeVO> out, CaseSnapshot b) {
        CaseInfo i = b.getInfo();
        if (i == null) {
            return;
        }
        add(out, "name", "案件名称", i.getName(), null, "REMOVE");
        add(out, "caseNo", "案件编号", i.getCaseNo(), null, "REMOVE");
        add(out, "status", "状态", dict("STATUS", i.getStatus()), null, "REMOVE");
        add(out, "deadline", "截止期限", fmt(i.getDeadline()), null, "REMOVE");
        add(out, "owner", "主办人", names(b, "OWNER"), null, "REMOVE");
        add(out, "members", "协办人", names(b, "MEMBER"), null, "REMOVE");
        add(out, "files", "附件", fileNames(b), null, "REMOVE");
    }

    private void compare(List<ChangeVO> out, String field, String label, String before, String after) {
        if (Objects.equals(before, after)) {
            return;
        }
        add(out, field, label, before, after, "UPDATE");
    }

    private void add(List<ChangeVO> out, String field, String label, String before, String after, String type) {
        ChangeVO c = new ChangeVO();
        c.setField(field);
        c.setLabel(label);
        c.setBefore(before);
        c.setAfter(after);
        c.setType(type);
        out.add(c);
    }

    /** 快照里某一角色的在手人员姓名（按 id 去重、保持顺序） */
    private String names(CaseSnapshot s, String role) {
        if (s == null || s.getAssignees() == null) {
            return null;
        }
        Map<Long, OrgEmployee> emp = employeeService.employeeMap();
        Set<String> names = new LinkedHashSet<>();
        for (CaseAssignee a : s.getAssignees()) {
            if (!"ACTIVE".equals(a.getStatus()) || !role.equals(a.getAssignRole())) {
                continue;
            }
            OrgEmployee e = emp.get(a.getEmployeeId());
            names.add(e == null ? "员工#" + a.getEmployeeId() : e.getName());
        }
        return names.isEmpty() ? null : String.join("、", names);
    }

    private String fileNames(CaseSnapshot s) {
        if (s == null || s.getFiles() == null || s.getFiles().isEmpty()) {
            return null;
        }
        return s.getFiles().stream().map(CaseFile::getFileName).collect(Collectors.joining("、"));
    }

    /** 快照里的嫌疑人姓名，顺序即录入顺序 */
    private String suspectNames(CaseSnapshot s) {
        if (s == null || s.getSuspects() == null || s.getSuspects().isEmpty()) {
            return null;
        }
        return s.getSuspects().stream().map(CaseSuspect::getName).collect(Collectors.joining("、"));
    }

    private String dict(String type, String code) {
        if (code == null) {
            return null;
        }
        String name = DictHolder.name(type, code);
        return name == null ? code : name;
    }

    private String fmt(LocalDateTime t) {
        return t == null ? null : t.format(FMT);
    }

    /** 提前提醒天数的展示：不提醒不要显示成空白，写清楚「不提醒」 */
    private String remindText(Integer days) {
        return days == null || days <= 0 ? "不提醒" : "提前 " + days + " 天";
    }

    /** 重点关注的展示：老快照没有该字段时按「否」 */
    private String focusText(Integer focus) {
        return focus != null && focus == 1 ? "是" : "否";
    }

    // ------------------------------------------------------------------
    // JSON 读写
    // ------------------------------------------------------------------

    public CaseSnapshot read(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, CaseSnapshot.class);
        } catch (Exception e) {
            log.warn("[CaseSnapshot] 快照解析失败，按不可回滚处理：{}", e.getMessage());
            return null;
        }
    }

    private Long caseIdOf(CaseSnapshot snap) {
        if (snap.getInfo() != null && snap.getInfo().getId() != null) {
            return snap.getInfo().getId();
        }
        return null;
    }

    private String toJson(CaseSnapshot s) {
        try {
            return objectMapper.writeValueAsString(s);
        } catch (Exception e) {
            throw new BizException("快照序列化失败：" + e.getMessage());
        }
    }
}
