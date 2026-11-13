package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.OperationLog;
import com.caseflow.entity.OrgEmployee;
import com.caseflow.entity.WorkLog;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.OrgEmployeeMapper;
import com.caseflow.mapper.WorkLogMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.security.CurrentUser;
import com.caseflow.support.LogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作记录本（2026-10-29）：民警个人的工作记事本。
 *
 * <p><b>权限（需求硬性要求）</b>：
 * <ul>
 *   <li>普通民警：只能看、只能写自己名下（employee_id 匹配）的记录；</li>
 *   <li>其余角色（所长/副所长/队长/法制员/研判员/系统管理员）：可检索查看任意民警的记录，
 *       也可代录（但代录仍落在目标民警名下）。</li>
 * </ul>
 *
 * <p>归属判定：记录挂在 {@code org_employee.id}（员工档案）上，不挂在账号上——
 * 账号可停用/重建，员工档案才是「这个人」的稳定标识。
 */
@Service
public class WorkLogService {

    @Resource
    private WorkLogMapper workLogMapper;
    @Resource
    private OrgEmployeeMapper employeeMapper;
    @Resource
    private CaseInfoMapper caseInfoMapper;
    @Resource
    private LogService logService;

    /**
     * 当前登录人是否「只限本人」：普通民警只能看自己的记录。
     * 研判员与全权限角色都是管理侧，可看任意民警。
     */
    private boolean isRestrictedToSelf() {
        return !AuthContext.canViewAllCases();
    }

    /** 当前登录人绑定的员工档案 ID（可能为 null：账号没绑档案） */
    private Long myEmployeeId() {
        CurrentUser u = AuthContext.get();
        return u == null ? null : u.getEmployeeId();
    }

    /**
     * 新增一条工作记录。
     *
     * <p>普通民警：强制落自己名下（忽略前端传的 employeeId）。
     * 管理侧：可指定目标民警（employeeId），未指定则落自己。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkLog add(Long employeeId, String content, Long caseId, String caseName) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写工作内容");
        }
        if (content.trim().length() > 1000) {
            throw new BizException("工作内容不能超过 1000 字");
        }
        Long targetEmp;
        if (isRestrictedToSelf()) {
            targetEmp = myEmployeeId();
            if (targetEmp == null) {
                throw new BizException(403, "当前账号未绑定民警档案，无法记录工作内容，请联系管理员");
            }
        } else {
            targetEmp = employeeId != null ? employeeId : myEmployeeId();
        }
        if (targetEmp == null) {
            throw new BizException("未指定记录归属的民警");
        }
        OrgEmployee emp = employeeMapper.selectById(targetEmp);
        if (emp == null) {
            throw new BizException("目标民警档案不存在");
        }

        CurrentUser u = AuthContext.get();
        WorkLog w = new WorkLog();
        w.setEmployeeId(targetEmp);
        w.setEmployeeName(emp.getName());
        w.setCreatorId(u == null ? null : u.getUserId());
        w.setContent(content.trim());
        w.setCaseId(caseId);
        w.setCaseName(caseName);
        w.setCreatedAt(LocalDateTime.now());
        w.setUpdatedAt(w.getCreatedAt());
        w.setSource("MANUAL");
        workLogMapper.insert(w);

        logService.log("WORKLOG", "WORKLOG_ADD", "EMPLOYEE", targetEmp,
                "记录工作：" + abbrev(w.getContent()), null, null);
        return w;
    }

    /**
     * 案件相关操作自动派生工作记录（2026-11-13）：由 {@code LogService.write} 在每次
     * 写操作落库后调用，把民警对案件的「办案行为」自动记入工作记录本，与手动记录并列。
     *
     * <p><b>只记录「办案民警」的实际办案行为</b>，过滤规则：
     * <ul>
     *   <li>必须是案件本体操作（module=CASE 且 targetType=CASE 且 targetId 非空）；</li>
     *   <li>操作人必须已绑定员工档案（employeeId 非空）——工作记录本挂在员工档案上，
     *       没绑档案的账号（如纯管理员）不产生自动记录；</li>
     *   <li>跳过「撤回」类动作（UNDO）——撤回是纠正，不是办案行为；</li>
     *   <li>登录/员工/附件等非案件模块不在此列。</li>
     * </ul>
     *
     * <p>失败绝不影响主流程（与信箱通知派生同一原则）：此处 try/catch 吞掉异常，
     * 只在日志里告警，绝不让一次写操作因工作记录派生失败而回滚。
     */
    public void onCaseOperation(OperationLog log) {
        try {
            if (log == null || !"CASE".equals(log.getModule())
                    || !"CASE".equals(log.getTargetType()) || log.getTargetId() == null) {
                return;
            }
            if ("UNDO".equals(log.getAction())) {
                return;
            }
            CurrentUser u = AuthContext.get();
            if (u == null || u.getEmployeeId() == null) {
                return;
            }
            // 内容：操作日志的 content 本身就是精心写的可读描述（如「待办反馈：123 → 已联系…」），
            // 直接复用即可；content 为空时用动作中文名兜底，避免拼出「提交工作反馈：待办反馈」这类重复。
            String body = log.getContent() == null ? "" : log.getContent().trim();
            String actionName = OperationLogService.actionName(log.getModule(), log.getAction());
            String content = StringUtils.hasText(body) ? body : actionName;
            content = content.length() > 1000 ? content.substring(0, 1000) : content;

            OrgEmployee emp = employeeMapper.selectById(u.getEmployeeId());
            CaseInfo c = caseInfoMapper.selectById(log.getTargetId());

            WorkLog w = new WorkLog();
            w.setEmployeeId(u.getEmployeeId());
            w.setEmployeeName(emp != null ? emp.getName() : u.getDisplayName());
            w.setCreatorId(u.getUserId());
            w.setContent(content);
            w.setCaseId(log.getTargetId());
            w.setCaseName(c != null ? c.getName() : null);
            w.setCreatedAt(LocalDateTime.now());
            w.setUpdatedAt(w.getCreatedAt());
            w.setSource("AUTO");
            w.setLogId(log.getId());
            workLogMapper.insert(w);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(WorkLogService.class)
                    .warn("[WorkLog] 案件操作自动记录派生失败（不影响业务）：{}", e.getMessage());
        }
    }

    /**
     * 修改一条记录。
     *
     * <p>普通民警只能改自己的；管理侧可改任意。归属（employee_id）不可通过编辑转移。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkLog update(Long id, String content, Long caseId, String caseName) {
        WorkLog w = workLogMapper.selectById(id);
        if (w == null) {
            throw new BizException("记录不存在或已被删除");
        }
        requireWritable(w);
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写工作内容");
        }
        if (content.trim().length() > 1000) {
            throw new BizException("工作内容不能超过 1000 字");
        }
        w.setContent(content.trim());
        w.setCaseId(caseId);
        w.setCaseName(caseName);
        w.setUpdatedAt(LocalDateTime.now());
        workLogMapper.updateById(w);
        logService.log("WORKLOG", "WORKLOG_UPDATE", "EMPLOYEE", w.getEmployeeId(),
                "修改工作记录：" + abbrev(w.getContent()), null, null);
        return w;
    }

    /** 删除一条记录：普通民警只能删自己的；管理侧可删任意。 */
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        WorkLog w = workLogMapper.selectById(id);
        if (w == null) {
            return;
        }
        requireWritable(w);
        workLogMapper.deleteById(id);
        logService.log("WORKLOG", "WORKLOG_DELETE", "EMPLOYEE", w.getEmployeeId(),
                "删除工作记录：" + abbrev(w.getContent()), null, null);
    }

    /**
     * 查询工作记录。
     *
     * @param employeeId 目标民警（普通民警强制忽略，只看自己）；管理侧可指定，null=全部
     * @param keyword    按内容 / 案件名模糊检索
     */
    public List<WorkLog> list(Long employeeId, String keyword) {
        LambdaQueryWrapper<WorkLog> w = new LambdaQueryWrapper<>();
        if (isRestrictedToSelf()) {
            Long me = myEmployeeId();
            if (me == null) {
                return new ArrayList<>();
            }
            w.eq(WorkLog::getEmployeeId, me);
        } else if (employeeId != null) {
            w.eq(WorkLog::getEmployeeId, employeeId);
        }
        if (StringUtils.hasText(keyword)) {
            String k = keyword.trim();
            w.and(q -> q.like(WorkLog::getContent, k).or().like(WorkLog::getCaseName, k)
                    .or().like(WorkLog::getEmployeeName, k));
        }
        w.orderByAsc(WorkLog::getCreatedAt).orderByAsc(WorkLog::getId);
        return workLogMapper.selectList(w);
    }

    /** 民警下拉（供管理侧按人筛选）：返回所有有档案的员工 id + 姓名 */
    public List<Map<String, Object>> employees() {
        List<OrgEmployee> all = employeeMapper.selectList(new LambdaQueryWrapper<OrgEmployee>()
                .orderByAsc(OrgEmployee::getName));
        List<Map<String, Object>> out = new ArrayList<>();
        for (OrgEmployee e : all) {
            Map<String, Object> m = new HashMap<>();
            m.put("employeeId", e.getId());
            m.put("name", e.getName());
            m.put("dept", e.getDept());
            out.add(m);
        }
        return out;
    }

    /** 写权限：管理侧任意；普通民警仅本人 */
    private void requireWritable(WorkLog w) {
        if (isRestrictedToSelf()) {
            Long me = myEmployeeId();
            if (me == null || !me.equals(w.getEmployeeId())) {
                throw new BizException(403, "只能修改或删除自己的工作记录");
            }
        }
    }

    private String abbrev(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
