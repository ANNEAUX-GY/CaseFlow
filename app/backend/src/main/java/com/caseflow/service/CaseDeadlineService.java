package com.caseflow.service;

import com.caseflow.entity.CaseDeadlineState;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseDeadlineStateMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.security.AuthContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.support.LogService;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Comparator;

/** 案件具体时限；第 N 天统一从措施/受案日期起加 N 天。 */
@Service
public class CaseDeadlineService {
    @Resource private CaseDeadlineStateMapper states;
    @Resource private CaseInfoMapper cases;
    @Resource private LogService logs;
    @Resource private CaseScopeService scope;
    @Resource private CaseAssigneeMapper assignees;

    @Data
    public static class Alert {
        private Long caseId;
        private String caseNo;
        private String caseName;
        private Item item;
        public Alert(CaseInfo c, Item item) {
            this.caseId = c.getId(); this.caseNo = c.getCaseNo(); this.caseName = c.getName(); this.item = item;
        }
    }

    public List<Alert> active(String caseType, String category, boolean focusOnly) {
        List<Alert> out = new ArrayList<>();
        Set<Long> visible = AuthContext.isFullAccess() ? null : scope.myVisibleCaseIds();
        if (visible != null && visible.isEmpty()) return out;
        LambdaQueryWrapper<CaseInfo> q = new LambdaQueryWrapper<CaseInfo>()
                .notIn(CaseInfo::getStatus, "DONE", "CANCELLED");
        if (visible != null) q.in(CaseInfo::getId, visible);
        if ("CRIMINAL".equals(caseType) || "ADMINISTRATIVE".equals(caseType)) q.eq(CaseInfo::getCaseType, caseType);
        else if ("OTHER".equals(caseType)) q.and(w -> w.notIn(CaseInfo::getCaseType, "CRIMINAL", "ADMINISTRATIVE").or().isNull(CaseInfo::getCaseType));
        if (focusOnly) q.eq(CaseInfo::getFocus, 1);
        for (CaseInfo c : cases.selectList(q)) {
            if (category != null && !category.isEmpty() && !("NONE".equals(category)
                    ? c.getCategory() == null || c.getCategory().trim().isEmpty() : category.equals(c.getCategory()))) continue;
            for (Item item : view(c.getId()).getItems()) if (item.isReminding()) out.add(new Alert(c, item));
        }
        out.sort(Comparator.comparing(a -> a.getItem().getDueAt()));
        return out;
    }

    @Data
    public static class Item {
        private String key;
        private String label;
        private LocalDateTime dueAt;
        private LocalDateTime remindAt;
        private LocalDateTime completedAt;
        private boolean reminding;
        private boolean overdue;
        public Item(String key, String label, LocalDateTime dueAt, LocalDateTime remindAt, LocalDateTime completedAt, LocalDateTime now) {
            this.key = key; this.label = label; this.dueAt = dueAt; this.remindAt = remindAt; this.completedAt = completedAt;
            this.reminding = completedAt == null && remindAt != null && !now.isBefore(remindAt);
            this.overdue = completedAt == null && dueAt != null && now.isAfter(dueAt);
        }
    }

    @Data
    public static class View {
        private CaseDeadlineState state;
        private List<Item> items;
        private boolean paused;
        private boolean canSubmit;
        private String detentionTerm;
    }

    public View view(Long caseId) {
        CaseInfo c = requireCase(caseId);
        CaseDeadlineState s = states.selectById(caseId);
        if (s == null) { s = new CaseDeadlineState(); s.setCaseId(caseId); }
        return calculate(c, s, LocalDateTime.now());
    }

    static View calculate(CaseInfo c, CaseDeadlineState s, LocalDateTime now) {
        List<Item> items = new ArrayList<>();
        boolean paused = s.getPausedAt() != null;
        if ("DETENTION".equals(c.getCaseMeasure()) && c.getMeasureDate() != null) {
            LocalDateTime base = c.getMeasureDate().plusSeconds(s.getPausedSeconds() == null ? 0 : s.getPausedSeconds());
            String term = s.getDetentionTerm() == null ? "3" : s.getDetentionTerm();
            if ("3".equals(term)) {
                add(items, "CHANGE_TERM", "第3天变更羁押期限（7天/30天/停止计算）", base.plusDays(3), base.plusDays(3), null, now);
            } else if ("7".equals(term)) {
                add(items, "HANDOFF", "第4天提醒移交预审或法制", base.plusDays(4), base.plusDays(4), first(s.getPretrialReceivedAt(), s.getLegalReceivedAt()), now);
                add(items, "PRETRIAL", "最迟第5天移交预审", base.plusDays(5), base.plusDays(5), s.getPretrialReceivedAt(), now);
                add(items, "LEGAL", "第6天移交法制", base.plusDays(6), base.plusDays(6), s.getLegalReceivedAt(), now);
            } else if ("30".equals(term)) {
                add(items, "HANDOFF", "第12天提醒移交预审或法制", base.plusDays(12), base.plusDays(12), first(s.getPretrialReceivedAt(), s.getLegalReceivedAt()), now);
                add(items, "PRETRIAL", "第15天移交预审", base.plusDays(15), base.plusDays(15), s.getPretrialReceivedAt(), now);
                add(items, "PROGRESS", "第19天跟进提交审核进度", base.plusDays(19), base.plusDays(19), s.getSubmittedAt(), now);
                add(items, "LEGAL", "第20天移交法制", base.plusDays(20), base.plusDays(20), s.getLegalReceivedAt(), now);
            }
            if (!"3".equals(term)) add(items, "DETENTION_END", "刑拘" + term + "天期限届满", base.plusDays(Integer.parseInt(term)), base.plusDays(Integer.parseInt(term) - 1), null, now);
        } else if ("BAIL".equals(c.getCaseMeasure()) && c.getMeasureDate() != null) {
            LocalDateTime base = c.getMeasureDate();
            add(items, "PRETRIAL", "三个月内移交预审（可呈二次逮捕）", base.plusMonths(3), base.plusMonths(3).minusWeeks(1), s.getPretrialReceivedAt(), now);
            add(items, "LEGAL", "半年移交法制／评估解除取保", base.plusMonths(6), base.plusMonths(6).minusWeeks(1), s.getLegalReceivedAt(), now);
            add(items, "BAIL_END", "取保候审一年期限届满", base.plusYears(1), base.plusYears(1).minusWeeks(1), null, now);
            LocalDateTime last = s.getLastSummonsAt() == null ? base : s.getLastSummonsAt();
            add(items, "SUMMONS", "三个月内传讯并录入时间", last.plusMonths(3), last.plusMonths(3).minusWeeks(1), null, now);
        } else if ("RESIDENCE".equals(c.getCaseMeasure()) && c.getMeasureDate() != null) {
            LocalDateTime base = c.getMeasureDate();
            add(items, "RESIDENCE_END", "监视居住六个月期限届满", base.plusMonths(6), base.plusMonths(6).minusWeeks(1), null, now);
            LocalDateTime last = s.getLastSummonsAt() == null ? base : s.getLastSummonsAt();
            LocalDateTime monthly = base.plusMonths(1);
            while (s.getLastSummonsAt() != null && !monthly.isAfter(s.getLastSummonsAt())
                    && monthly.isBefore(base.plusMonths(6))) monthly = monthly.plusMonths(1);
            if (monthly.isAfter(base.plusMonths(6))) monthly = base.plusMonths(6);
            add(items, "SUMMONS_MONTH", "每月传讯提醒（录入实际时间）", monthly, monthly.minusWeeks(1), null, now);
            add(items, "SUMMONS_MAX", "距上次传讯不得超过三个月", last.plusMonths(3), last.plusMonths(3).minusWeeks(1), null, now);
        }
        if ("ADMINISTRATIVE".equals(c.getCaseType()) && c.getAcceptTime() != null) {
            LocalDateTime base = c.getAcceptTime();
            add(items, "ADMIN_CHECK_3", "第3天所内部监督", base.plusDays(3), base.plusDays(3), s.getAdminCheck3At(), now);
            add(items, "ADMIN_CHECK_10", "第10天所内部监督", base.plusDays(10), base.plusDays(10), s.getAdminCheck10At(), now);
            add(items, "ADMIN_CHECK_20", "第20天所内部监督", base.plusDays(20), base.plusDays(20), s.getAdminCheck20At(), now);
            add(items, "ADMIN_30", "第30天前呈30日延长办案期限文书", base.plusDays(29), base.plusDays(29), s.getAdmin30DocAt(), now);
            add(items, "ADMIN_60", "第60天前呈60日延长办案期限文书", base.plusDays(59), base.plusDays(59), s.getAdmin60DocAt(), now);
        }
        if (paused) for (Item item : items) { item.setReminding(false); item.setOverdue(false); }
        View v = new View(); v.setState(s); v.setItems(items); v.setPaused(paused);
        v.setDetentionTerm(s.getDetentionTerm() == null ? "3" : s.getDetentionTerm());
        v.setCanSubmit(s.getPretrialReceivedAt() != null || s.getLegalReceivedAt() != null);
        return v;
    }

    private static void add(List<Item> out, String key, String label, LocalDateTime due, LocalDateTime remind, LocalDateTime done, LocalDateTime now) {
        out.add(new Item(key, label, due == null ? null : due.toLocalDate().atTime(23, 59, 59),
                remind == null ? null : remind.toLocalDate().atStartOfDay(), done, now));
    }
    private static LocalDateTime first(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    @Transactional(rollbackFor = Exception.class)
    public View changeTerm(Long caseId, String action) {
        CaseInfo c = requireCase(caseId);
        if (!AuthContext.isFullAccess()) throw new BizException(403, "变更羁押期限需管理层操作");
        if (!"DETENTION".equals(c.getCaseMeasure()) || c.getMeasureDate() == null) throw new BizException("请先登记刑拘日期");
        CaseDeadlineState s = state(caseId);
        LocalDateTime now = LocalDateTime.now();
        switch (action) {
            case "7": case "30":
                if (s.getPausedAt() != null) throw new BizException("请先恢复计算");
                s.setDetentionTerm(action);
                c.setDetainDeadline(c.getMeasureDate().plusSeconds(s.getPausedSeconds()).plusDays(Integer.parseInt(action))
                        .toLocalDate().atTime(23, 59, 59));
                break;
            case "PAUSE":
                if (s.getPausedAt() != null) throw new BizException("已经停止计算");
                s.setPausedAt(now);
                s.setPausedDeadline(c.getDetainDeadline() != null ? c.getDetainDeadline()
                        : c.getMeasureDate().plusSeconds(s.getPausedSeconds()).plusDays(Integer.parseInt(
                        s.getDetentionTerm() == null ? "3" : s.getDetentionTerm())).toLocalDate().atTime(23, 59, 59));
                c.setDetainDeadline(null);
                break;
            case "RESUME":
                if (s.getPausedAt() == null) throw new BizException("尚未停止计算");
                long seconds = Duration.between(s.getPausedAt(), now).getSeconds();
                s.setPausedSeconds(s.getPausedSeconds() + seconds);
                c.setDetainDeadline(s.getPausedDeadline().plusSeconds(seconds));
                s.setPausedAt(null); s.setPausedDeadline(null);
                break;
            default: throw new BizException("请选择7天、30天、停止计算或恢复计算");
        }
        save(s);
        c.setUpdatedAt(now); cases.updateById(c);
        cases.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CaseInfo>()
                .eq(CaseInfo::getId, caseId).set(CaseInfo::getDetainDeadline, c.getDetainDeadline()));
        logs.log("CASE", "MEASURE", "CASE", caseId, "变更羁押期限：" + action);
        return view(caseId);
    }

    @Transactional(rollbackFor = Exception.class)
    public View record(Long caseId, String key, LocalDateTime at) {
        CaseInfo c = requireCase(caseId);
        checkHandlerOrManager(c);
        if (at == null) throw new BizException("请填写有效的实际办理时间");
        if (at.isAfter(LocalDateTime.now())) throw new BizException("实际办理时间不能晚于现在");
        if (key == null) throw new BizException("请选择时限节点");
        if ((key.startsWith("ADMIN_") || "ACCEPT".equals(key)) && !"ADMINISTRATIVE".equals(c.getCaseType()))
            throw new BizException("仅行政案件可登记该节点");
        if (("PRETRIAL".equals(key) || "LEGAL".equals(key))
                && !"DETENTION".equals(c.getCaseMeasure()) && !"BAIL".equals(c.getCaseMeasure()))
            throw new BizException("当前强制措施不适用该收件节点");
        if ("SUMMONS".equals(key) && !"BAIL".equals(c.getCaseMeasure())
                && !"RESIDENCE".equals(c.getCaseMeasure()))
            throw new BizException("当前强制措施不适用传讯节点");
        CaseDeadlineState s = state(caseId);
        LocalDateTime date = at;
        switch (key) {
            case "ACCEPT":
                if (!"ADMINISTRATIVE".equals(c.getCaseType())) throw new BizException("仅行政案件登记受案时间");
                c.setAcceptTime(date); cases.updateById(c); break;
            case "PRETRIAL": s.setPretrialReceivedAt(date); break;
            case "LEGAL": s.setLegalReceivedAt(date); break;
            case "SUMMONS": s.setLastSummonsAt(date); break;
            case "ADMIN_30": s.setAdmin30DocAt(date); break;
            case "ADMIN_60": s.setAdmin60DocAt(date); break;
            case "ADMIN_CHECK_3": s.setAdminCheck3At(date); break;
            case "ADMIN_CHECK_10": s.setAdminCheck10At(date); break;
            case "ADMIN_CHECK_20": s.setAdminCheck20At(date); break;
            default: throw new BizException("不支持的时限节点");
        }
        save(s);
        logs.log("CASE", "MEASURE", "CASE", caseId, "登记时限节点 " + key + "：" + date);
        return view(caseId);
    }

    private CaseDeadlineState state(Long id) {
        CaseDeadlineState s = states.selectById(id);
        if (s == null) { s = new CaseDeadlineState(); s.setCaseId(id); s.setPausedSeconds(0L); }
        if (s.getPausedSeconds() == null) s.setPausedSeconds(0L);
        return s;
    }
    private void save(CaseDeadlineState s) {
        s.setUpdatedAt(LocalDateTime.now());
        if (states.selectById(s.getCaseId()) == null) states.insert(s); else {
            states.updateById(s);
            // MyBatis-Plus 默认跳过 null，恢复计时必须显式清空暂停标记。
            if (s.getPausedAt() == null) states.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CaseDeadlineState>()
                    .eq(CaseDeadlineState::getCaseId, s.getCaseId())
                    .set(CaseDeadlineState::getPausedAt, null)
                    .set(CaseDeadlineState::getPausedDeadline, null));
        }
    }
    private CaseInfo requireCase(Long id) {
        CaseInfo c = cases.selectById(id);
        if (c == null) throw new BizException("案件不存在");
        if (!AuthContext.isFullAccess() && !scope.myVisibleCaseIds().contains(id)) throw new BizException(403, "无权查看该案件时限");
        return c;
    }
    private void checkHandlerOrManager(CaseInfo c) {
        if (AuthContext.isFullAccess()) return;
        Long emp = AuthContext.get() == null ? null : AuthContext.get().getEmployeeId();
        if (emp == null || assignees.selectCount(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getCaseId, c.getId())
                .eq(CaseAssignee::getEmployeeId, emp)
                .eq(CaseAssignee::getStatus, "ACTIVE")) == 0) throw new BizException(403, "仅本案承办人可登记办理时间");
    }
}
