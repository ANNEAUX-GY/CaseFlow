package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.caseflow.common.PageResult;
import com.caseflow.dto.AssignRequest;
import com.caseflow.dto.CaseQuery;
import com.caseflow.dto.CaseSaveRequest;
import com.caseflow.dto.StatusRequest;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseFile;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseSuspect;
import com.caseflow.entity.OrgEmployee;
import com.caseflow.entity.SysUser;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.SysUserMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.security.Roles;
import com.caseflow.support.DictHolder;
import com.caseflow.support.LogService;
import com.caseflow.vo.AssigneeVO;
import com.caseflow.vo.CaseVO;
import com.caseflow.vo.DashboardVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 案件主流程：创建 -> 指派 -> 流转 -> 办结；并对接到期提醒与统计。
 */
@Service
public class CaseService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 只有管理层能做的状态流转：办结 DONE、撤销 CANCELLED。
     * 普通民警仍可把案件从「已指派」推进到「处理中」，但无权结案。
     */
    private static final Set<String> MANAGER_ONLY_STATUS = new HashSet<>(
            java.util.Arrays.asList("DONE", "CANCELLED"));

    /** 合法的案件状态取值，防止脏值写库 */
    private static final Set<String> VALID_STATUS = Collections.unmodifiableSet(new HashSet<>(
            java.util.Arrays.asList("PENDING_ASSIGN", "ASSIGNED", "IN_PROGRESS", "DONE", "CANCELLED")));

    /**
     * 必须有承办人才能进入的状态：办理中、已办结。
     * 一个没有人承办的案件不可能正在办理、更不可能已经办结；
     * 放任这样写库会留下无法追溯的记录（谁办的？）。
     * 撤销 CANCELLED 与回退 PENDING_ASSIGN 不要求承办人。
     */
    private static final Set<String> NEED_ASSIGNEE_STATUS = Collections.unmodifiableSet(new HashSet<>(
            java.util.Arrays.asList("IN_PROGRESS", "DONE")));

    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CaseAssigneeMapper assigneeMapper;
    @Resource
    private SysUserMapper userMapper;
    @Resource
    private EmployeeService employeeService;
    @Resource
    private FileService fileService;
    @Resource
    private LogService logService;
    @Resource
    private CaseSnapshotService snapshotService;
    @Resource
    private OperationLogService operationLogService;
    @Resource
    private TodoService todoService;
    @Resource
    private com.caseflow.mapper.CaseSuspectMapper suspectMapper;
    @Resource
    private SuspectService suspectService;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public PageResult<CaseVO> page(CaseQuery query) {
        Map<Long, OrgEmployee> empMap = employeeService.employeeMap();
        Map<Long, String> pathMap = employeeService.pathNameMap();
        LambdaQueryWrapper<CaseInfo> q = buildWrapper(query);

        int page = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int size = query.getSize() == null || query.getSize() < 1 ? 20 : Math.min(query.getSize(), 200);
        IPage<CaseInfo> p = new Page<>(page, size);
        IPage<CaseInfo> result = caseMapper.selectPage(p, q);

        // 嫌疑人数量一次批量算完，避免列表里逐行查库
        Map<Long, Integer> suspectCounts = suspectCounts(result.getRecords().stream()
                .map(CaseInfo::getId).collect(Collectors.toList()));
        List<CaseVO> list = result.getRecords().stream()
                .map(c -> toVO(c, false, empMap, pathMap, suspectCounts))
                .collect(Collectors.toList());
        return new PageResult<>(list, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 当前登录人「名下案件」的 id 集合，是列表 / 提醒 / 看板 / 图表的统一口径，
     * 避免各处各写一套筛选而出现口径不一致。
     *
     * <p>已绑定员工账号 -> 取该员工在办指派的案件（case_assignee.status = ACTIVE）；
     * 未绑定员工的账号（存量 boss / test1 之类）-> 退回「本人创建的案件」，
     * 至少不会看到别人的数据。
     */
    public Set<Long> myVisibleCaseIds() {
        com.caseflow.security.CurrentUser u = AuthContext.get();
        if (u == null) {
            return Collections.emptySet();
        }
        Long empId = u.getEmployeeId();
        if (empId != null) {
            return assigneeMapper.selectList(new LambdaQueryWrapper<CaseAssignee>()
                            .eq(CaseAssignee::getEmployeeId, empId)
                            .eq(CaseAssignee::getStatus, "ACTIVE"))
                    .stream().map(CaseAssignee::getCaseId).collect(Collectors.toSet());
        }
        return caseMapper.selectList(new LambdaQueryWrapper<CaseInfo>()
                        .eq(CaseInfo::getCreatedBy, u.getUserId()))
                .stream().map(CaseInfo::getId).collect(Collectors.toSet());
    }

    /**
     * 是否把查询范围收敛到「本人」。
     * 管理层（所长 / 副所长 / 法制员 / 系统管理员）看全部，只有显式声明只看自己时才收敛；
     * 普通民警恒为 true。
     */
    private boolean scopeToSelf(CaseQuery query) {
        if (!AuthContext.isFullAccess()) {
            return true;
        }
        return Boolean.TRUE.equals(query.getOnlyMine());
    }

    private LambdaQueryWrapper<CaseInfo> buildWrapper(CaseQuery query) {
        LambdaQueryWrapper<CaseInfo> q = new LambdaQueryWrapper<>();
        LocalDateTime now = LocalDateTime.now();

        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            q.and(w -> w.like(CaseInfo::getName, kw)
                    .or().like(CaseInfo::getCaseNo, kw)
                    .or().like(CaseInfo::getCategory, kw)
                    .or().like(CaseInfo::getFilingNo, kw)
                    .or().like(CaseInfo::getDescription, kw));
        }
        if (StringUtils.hasText(query.getStatus())) {
            if ("OPEN".equals(query.getStatus())) {
                q.in(CaseInfo::getStatus, new ArrayList<>(java.util.Arrays.asList(
                        "PENDING_ASSIGN", "ASSIGNED", "IN_PROGRESS")));
            } else {
                q.eq(CaseInfo::getStatus, query.getStatus());
            }
        }
        if (StringUtils.hasText(query.getPriority())) {
            q.eq(CaseInfo::getPriority, query.getPriority());
        }
        if (StringUtils.hasText(query.getSourceType())) {
            q.eq(CaseInfo::getSourceType, query.getSourceType());
        }
        if (StringUtils.hasText(query.getCategory())) {
            q.eq(CaseInfo::getCategory, query.getCategory());
        }
        if (StringUtils.hasText(query.getCaseType())) {
            String ct = query.getCaseType().trim();
            if ("OTHER".equalsIgnoreCase(ct)) {
                // 「其他案件」= 一切非刑事、非行政的案件（2026-10 统一类型选择器口径）。
                // 包含：未立案(PRELIMINARY)、历史上未填类型的空值，以及将来新增的其他大类。
                // 用 NOT IN + IS NULL 而不是等值匹配，否则「未分类」的存量案件会漏掉。
                q.and(w -> w.notIn(CaseInfo::getCaseType, "CRIMINAL", "ADMINISTRATIVE")
                        .or().isNull(CaseInfo::getCaseType));
            } else {
                q.eq(CaseInfo::getCaseType, ct);
            }
        }
        if (StringUtils.hasText(query.getHasSuspect())) {
            String sql = "SELECT 1 FROM case_suspect s WHERE s.case_id = case_info.id";
            if ("YES".equalsIgnoreCase(query.getHasSuspect())) {
                q.exists(sql);
            } else {
                q.notExists(sql);
            }
        }
        // ---- 案件盯办：子模块视图 / 侦查进度 / 嫌疑人检索 ----
        if (StringUtils.hasText(query.getModule())) {
            String m = query.getModule().trim();
            if ("INITIAL".equals(m)) {
                // 初查案件：未采取强制措施 + 在办（未办结/未撤销）
                q.and(w -> w.isNull(CaseInfo::getCaseMeasure).or().eq(CaseInfo::getCaseMeasure, "NONE"))
                        .notIn(CaseInfo::getStatus, new ArrayList<>(java.util.Arrays.asList("DONE", "CANCELLED")));
            } else if ("DETENTION".equals(m)) {
                q.eq(CaseInfo::getCaseMeasure, "DETENTION");
            } else if ("BAIL_RESIDENCE".equals(m)) {
                q.in(CaseInfo::getCaseMeasure, new ArrayList<>(java.util.Arrays.asList("BAIL", "RESIDENCE")));
            }
        }
        if (StringUtils.hasText(query.getInvestigationStatus())) {
            q.eq(CaseInfo::getInvestigationStatus, query.getInvestigationStatus());
        }
        if (StringUtils.hasText(query.getSuspectName())) {
            q.apply("EXISTS (SELECT 1 FROM case_suspect s WHERE s.case_id = case_info.id AND s.name LIKE {0})",
                    "%" + query.getSuspectName().trim() + "%");
        }
        if (StringUtils.hasText(query.getSuspectIdCard())) {
            q.apply("EXISTS (SELECT 1 FROM case_suspect s WHERE s.case_id = case_info.id AND s.id_card LIKE {0})",
                    query.getSuspectIdCard().trim() + "%");
        }
        if (query.getEmployeeId() != null) {
            List<Long> caseIds = assigneeMapper.selectList(new LambdaQueryWrapper<CaseAssignee>()
                            .eq(CaseAssignee::getEmployeeId, query.getEmployeeId())
                            .eq(CaseAssignee::getStatus, "ACTIVE"))
                    .stream().map(CaseAssignee::getCaseId).distinct().collect(Collectors.toList());
            if (caseIds.isEmpty()) {
                q.eq(CaseInfo::getId, -1L);
            } else {
                q.in(CaseInfo::getId, caseIds);
            }
        }
        // 数据范围：管理层看全部，普通民警恒为「本人名下」的案件 —
        // 普通民警即便手工传 onlyMine=false 也不放行，避免越过界面直接越权拉全量。
        if (scopeToSelf(query)) {
            Collection<Long> mine = myVisibleCaseIds();
            if (mine.isEmpty()) {
                // 名下无案件：用恒假条件占位，避免 in () 空集合的语法问题
                q.eq(CaseInfo::getId, -1L);
            } else {
                q.in(CaseInfo::getId, mine);
            }
        }
        if (StringUtils.hasText(query.getDueBucket())) {
            LocalDate today = now.toLocalDate();
            switch (query.getDueBucket()) {
                case "OVERDUE":
                    q.isNotNull(CaseInfo::getDeadline).lt(CaseInfo::getDeadline, now)
                            .notIn(CaseInfo::getStatus, new ArrayList<>(java.util.Arrays.asList("DONE", "CANCELLED")));
                    break;
                case "TODAY":
                    q.isNotNull(CaseInfo::getDeadline)
                            .ge(CaseInfo::getDeadline, today.atStartOfDay())
                            .lt(CaseInfo::getDeadline, today.plusDays(1).atStartOfDay());
                    break;
                case "D3":
                    q.isNotNull(CaseInfo::getDeadline)
                            .ge(CaseInfo::getDeadline, now)
                            .lt(CaseInfo::getDeadline, today.plusDays(4).atStartOfDay());
                    break;
                case "D7":
                    q.isNotNull(CaseInfo::getDeadline)
                            .ge(CaseInfo::getDeadline, now)
                            .lt(CaseInfo::getDeadline, today.plusDays(8).atStartOfDay());
                    break;
                case "NONE":
                    q.isNull(CaseInfo::getDeadline);
                    break;
                default:
                    break;
            }
        }

        String field = query.getSortField() == null ? "created_at" : query.getSortField();
        boolean asc = "asc".equalsIgnoreCase(query.getSortOrder());
        switch (field) {
            case "deadline":
                q.orderBy(true, asc, CaseInfo::getDeadline);
                q.orderBy(true, false, CaseInfo::getCreatedAt);
                break;
            case "priority":
                q.orderBy(true, asc, CaseInfo::getPriority);
                break;
            case "updated_at":
                q.orderBy(true, asc, CaseInfo::getUpdatedAt);
                break;
            default:
                q.orderBy(true, asc, CaseInfo::getCreatedAt);
                break;
        }
        return q;
    }

    private CaseVO toVO(CaseInfo c, boolean full, Map<Long, OrgEmployee> empMap, Map<Long, String> pathMap,
                        Map<Long, Integer> suspectCounts) {
        CaseVO vo = new CaseVO();
        vo.setId(c.getId());
        vo.setCaseNo(c.getCaseNo());
        vo.setName(c.getName());
        vo.setSourceType(c.getSourceType());
        vo.setSourceTypeName(DictHolder.name("SOURCE_TYPE", c.getSourceType()));
        vo.setSourceFileId(c.getSourceFileId());
        vo.setCaseType(c.getCaseType());
        vo.setCaseTypeName(DictHolder.name("CASE_TYPE", c.getCaseType()));
        vo.setCategory(c.getCategory());
        vo.setFilingNo(c.getFilingNo());
        vo.setMediationNo(c.getMediationNo());
        vo.setDescription(c.getDescription());
        vo.setPriority(c.getPriority());
        vo.setPriorityName(DictHolder.name("PRIORITY", c.getPriority()));
        vo.setDeadline(c.getDeadline());
        vo.setDeadlineText(c.getDeadline() == null ? null : c.getDeadline().format(FMT));
        LocalDateTime now = LocalDateTime.now();
        vo.setDaysLeft(DictHolder.daysLeft(c.getDeadline(), now));
        vo.setDueLevel(DictHolder.dueLevel(c.getDeadline(), now));
        vo.setStatus(c.getStatus());
        vo.setStatusName(DictHolder.name("STATUS", c.getStatus()));
        // ---- 盯办字段：强制措施 / 侦查进度 / 措施期限倒计时 ----
        vo.setCaseMeasure(c.getCaseMeasure());
        vo.setCaseMeasureName(DictHolder.name("CASE_MEASURE", c.getCaseMeasure()));
        vo.setMeasureDate(c.getMeasureDate());
        vo.setDetainDeadline(c.getDetainDeadline());
        vo.setDetainDeadlineText(c.getDetainDeadline() == null ? null : c.getDetainDeadline().format(FMT));
        vo.setDetainDaysLeft(DictHolder.daysLeft(c.getDetainDeadline(), now));
        vo.setInvestigationStatus(c.getInvestigationStatus());
        vo.setInvestigationStatusName(DictHolder.name("INVESTIGATION_STATUS", c.getInvestigationStatus()));
        vo.setRemark(c.getRemark());
        vo.setCreatedByName(userName(c.getCreatedBy()));
        vo.setCreatedAt(c.getCreatedAt());
        vo.setUpdatedAt(c.getUpdatedAt());

        Integer count = suspectCounts == null ? null : suspectCounts.get(c.getId());
        vo.setSuspectCount(count == null ? 0 : count);

        List<AssigneeVO> all = assigneesOf(c.getId(), empMap, pathMap);
        vo.setOwner(all.stream().filter(a -> "OWNER".equals(a.getAssignRole()) && "ACTIVE".equals(a.getStatus()))
                .findFirst().orElse(null));
        vo.setMembers(all.stream().filter(a -> !"OWNER".equals(a.getAssignRole()) && "ACTIVE".equals(a.getStatus()))
                .collect(Collectors.toList()));
        if (full) {
            vo.setAssignHistory(all);
            vo.setFiles(fileService.filesOf(c.getId()));
            vo.setSuspects(suspectService.listOf(c.getId()));
        }
        return vo;
    }

    private CaseVO toVO(CaseInfo c, boolean full, Map<Long, OrgEmployee> empMap, Map<Long, String> pathMap) {
        Map<Long, Integer> counts = full
                ? suspectCounts(java.util.Collections.singletonList(c.getId()))
                : null;
        return toVO(c, full, empMap, pathMap, counts);
    }

    /** 批量统计各案件的嫌疑人数（一次 group by，避免 N+1） */
    private Map<Long, Integer> suspectCounts(List<Long> caseIds) {
        Map<Long, Integer> map = new HashMap<>();
        if (caseIds == null || caseIds.isEmpty()) {
            return map;
        }
        List<CaseSuspect> list = suspectMapper.selectList(new LambdaQueryWrapper<CaseSuspect>()
                .in(CaseSuspect::getCaseId, caseIds));
        for (CaseSuspect s : list) {
            map.merge(s.getCaseId(), 1, Integer::sum);
        }
        return map;
    }

    public List<AssigneeVO> assigneesOf(Long caseId, Map<Long, OrgEmployee> empMap, Map<Long, String> pathMap) {
        List<CaseAssignee> list = assigneeMapper.selectList(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getCaseId, caseId).orderByAsc(CaseAssignee::getId));
        List<AssigneeVO> vos = new ArrayList<>();
        for (CaseAssignee a : list) {
            AssigneeVO vo = new AssigneeVO();
            vo.setId(a.getId());
            vo.setEmployeeId(a.getEmployeeId());
            OrgEmployee e = empMap.get(a.getEmployeeId());
            vo.setEmployeeName(e == null ? "（已离职/已删除）" : e.getName());
            vo.setDept(e == null ? null : e.getDept());
            vo.setTitle(e == null ? null : e.getTitle());
            vo.setPathName(pathMap.get(a.getEmployeeId()));
            vo.setAssignRole(a.getAssignRole());
            vo.setNote(a.getNote());
            vo.setStatus(a.getStatus());
            vo.setAssignedAt(a.getAssignedAt());
            vos.add(vo);
        }
        return vos;
    }

    private String userName(Long id) {
        if (id == null) {
            return null;
        }
        SysUser u = userMapper.selectById(id);
        return u == null ? null : u.getDisplayName();
    }

    public CaseVO detail(Long id) {
        CaseInfo c = caseMapper.selectById(id);
        if (c == null) {
            throw new BizException("案件不存在");
        }
        CaseVO vo = toVO(c, true, employeeService.employeeMap(), employeeService.pathNameMap());
        // 待办进度只在详情页填充，避免列表页逐行查询（N+1）
        int[] tc = todoService.countsOf(id);
        vo.setTodoDone(tc[0]);
        vo.setTodoTotal(tc[1]);
        return vo;
    }

    // ------------------------------------------------------------------
    // 创建 / 编辑
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public CaseVO save(CaseSaveRequest req) {
        LocalDateTime now = LocalDateTime.now();
        CaseInfo c = new CaseInfo();
        boolean isNew = req.getId() == null;
        CaseInfo existing = null;
        // 操作前快照：新建时是「案件尚不存在」，编辑时是改之前的完整状态
        String beforeSnapshot;
        if (isNew) {
            beforeSnapshot = snapshotService.absentJson();
            c.setCaseNo(generateCaseNo());
            c.setCreatedBy(AuthContext.userId());
            c.setCreatedAt(now);
        } else {
            existing = caseMapper.selectById(req.getId());
            if (existing == null) {
                throw new BizException("案件不存在");
            }
            beforeSnapshot = snapshotService.capture(req.getId());
            c.setId(existing.getId());
            c.setCaseNo(existing.getCaseNo());
            c.setCreatedBy(existing.getCreatedBy());
            c.setCreatedAt(existing.getCreatedAt());
        }
        // 案件类型（大类）强制必选：手动新增与上传材料建案都走这里，杜绝"未分类"案件再产生
        if (!StringUtils.hasText(req.getCaseType())) {
            throw new BizException("请选择案件类型");
        }
        c.setName(req.getName().trim());
        c.setCaseType(req.getCaseType());
        c.setCategory(req.getCategory());
        c.setFilingNo(req.getFilingNo());
        c.setMediationNo(req.getMediationNo());
        c.setDescription(req.getDescription());
        c.setPriority(StringUtils.hasText(req.getPriority()) ? req.getPriority() : "NORMAL");
        c.setDeadline(req.getDeadline());
        // 状态：显式传入才生效，且只接受「待指派 / 已指派」两个起点状态。
        // 编辑时不传则沿用原状态——前端编辑表单不提交 status，若这里回落到
        // PENDING_ASSIGN，会把在办案件打回「待指派」，与仍在的承办人相矛盾。
        // 进入「办理中 / 已办结 / 已撤销」统一走 POST /cases/{id}/status（有承办人与权限校验）。
        String reqStatus = req.getStatus();
        if (StringUtils.hasText(reqStatus)) {
            if (!"PENDING_ASSIGN".equals(reqStatus) && !"ASSIGNED".equals(reqStatus)) {
                throw new BizException("不能通过建案/编辑把状态设为「" + DictHolder.name("STATUS", reqStatus)
                        + "」，请使用状态流转操作。");
            }
            // 「已指派」必须同时有人：否则会造出「已指派但没有承办人」的另一种矛盾数据
            if ("ASSIGNED".equals(reqStatus)
                    && req.getOwnerId() == null
                    && (req.getMemberIds() == null || req.getMemberIds().isEmpty())) {
                throw new BizException("把状态设为「已指派」时必须同时指定承办人");
            }
            c.setStatus(reqStatus);
        } else {
            c.setStatus(isNew ? "PENDING_ASSIGN" : existing.getStatus());
        }
        c.setRemark(req.getRemark());
        c.setUpdatedAt(now);

        // 来源类型：优先以附件扩展名判定，其次用前端传入值
        String sourceType = req.getSourceType();
        Long fileId = req.getSourceFileId();
        if (fileId != null) {
            CaseFile f = fileService.get(fileId);
            String detected = FileService.detectSourceType(f.getFileName());
            if (!"MANUAL".equals(detected)) {
                sourceType = detected;
            }
            c.setSourceFileId(fileId);
            fileService.bindToCase(fileId, c.getId());
        }
        c.setSourceType(StringUtils.hasText(sourceType) ? sourceType : "MANUAL");

        if (isNew) {
            caseMapper.insert(c);
        } else {
            caseMapper.updateById(c);
        }

        // 创建时直接指派
        if (req.getOwnerId() != null || (req.getMemberIds() != null && !req.getMemberIds().isEmpty())) {
            AssignRequest ar = new AssignRequest();
            ar.setCaseId(c.getId());
            ar.setOwnerId(req.getOwnerId());
            ar.setMemberIds(req.getMemberIds());
            ar.setNote(req.getAssignNote());
            assign(ar);
        }
        // 快照写在最后：这样 after 里连指派关系一起带上，撤回时能整体回到操作前
        String after = snapshotService.capture(c.getId());
        logService.log("CASE", isNew ? "CREATE" : "UPDATE", "CASE", c.getId(),
                (isNew ? "创建案件：" : "更新案件：") + c.getName(), beforeSnapshot, after);
        return detail(c.getId());
    }

    private String generateCaseNo() {
        String day = LocalDate.now().format(DAY);
        String prefix = "CA-" + day + "-";
        long count = caseMapper.selectCount(new LambdaQueryWrapper<CaseInfo>().likeRight(CaseInfo::getCaseNo, prefix));
        return prefix + String.format("%03d", count + 1);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        CaseInfo c = caseMapper.selectById(id);
        if (c == null) {
            throw new BizException("案件不存在");
        }
        // 留下完整快照，之后可以原样撤回（连案件 id 一起还原）
        String before = snapshotService.capture(id);
        assigneeMapper.delete(new LambdaQueryWrapper<CaseAssignee>().eq(CaseAssignee::getCaseId, id));
        suspectMapper.delete(new LambdaQueryWrapper<CaseSuspect>().eq(CaseSuspect::getCaseId, id));
        todoService.removeAllOfCase(id);
        caseMapper.deleteById(id);
        logService.log("CASE", "DELETE", "CASE", id, "删除案件：" + c.getName(),
                before, snapshotService.absentJson());
    }

    // ------------------------------------------------------------------
    // 指派 / 改派
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public CaseVO assign(AssignRequest req) {
        if (req.getCaseId() == null) {
            throw new BizException("缺少案件 ID");
        }
        CaseInfo c = caseMapper.selectById(req.getCaseId());
        if (c == null) {
            throw new BizException("案件不存在");
        }
        if ("DONE".equals(c.getStatus()) || "CANCELLED".equals(c.getStatus())) {
            throw new BizException("已办结/已撤销的案件不能再指派");
        }

        Set<Long> target = new HashSet<>();
        if (req.getOwnerId() != null) {
            target.add(req.getOwnerId());
        }
        if (req.getMemberIds() != null) {
            for (Long m : req.getMemberIds()) {
                if (m != null && !m.equals(req.getOwnerId())) {
                    target.add(m);
                }
            }
        }
        if (target.isEmpty()) {
            throw new BizException("请至少选择一名承办人");
        }
        for (Long empId : target) {
            OrgEmployee e = employeeService.employeeMap().get(empId);
            if (e == null) {
                throw new BizException("员工不存在：ID=" + empId);
            }
        }

        // 旧指派关系全部置为历史（改派留痕），再写入新关系
        String beforeSnapshot = snapshotService.capture(c.getId());
        List<CaseAssignee> oldList = assigneeMapper.selectList(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getCaseId, c.getId()).eq(CaseAssignee::getStatus, "ACTIVE"));
        LocalDateTime now = LocalDateTime.now();
        for (CaseAssignee old : oldList) {
            if (target.contains(old.getEmployeeId())) {
                target.remove(old.getEmployeeId());
            }
            old.setStatus("REPLACED");
            old.setClosedAt(now);
            assigneeMapper.updateById(old);
        }

        List<Long> ordered = new ArrayList<>(target);
        Long ownerId = req.getOwnerId();
        boolean ownerFromOld = false;
        if (ownerId == null) {
            for (CaseAssignee old : oldList) {
                if ("OWNER".equals(old.getAssignRole())) {
                    ownerId = old.getEmployeeId();
                    ownerFromOld = true;
                    break;
                }
            }
        }
        if (ownerId == null && !ordered.isEmpty()) {
            ownerId = ordered.get(0);
        }
        if (ownerFromOld || (ownerId != null && !target.contains(ownerId))) {
            // 主办人沿用旧值且未被替换：重新激活该关系
            for (CaseAssignee old : oldList) {
                if (old.getEmployeeId().equals(ownerId)) {
                    old.setStatus("ACTIVE");
                    old.setClosedAt(null);
                    assigneeMapper.updateById(old);
                }
            }
        }

        for (Long empId : ordered) {
            CaseAssignee a = new CaseAssignee();
            a.setCaseId(c.getId());
            a.setEmployeeId(empId);
            a.setAssignRole(empId.equals(ownerId) ? "OWNER" : "MEMBER");
            a.setNote(req.getNote());
            a.setStatus("ACTIVE");
            a.setAssignedBy(AuthContext.userId());
            a.setAssignedAt(now);
            assigneeMapper.insert(a);
        }

        // 状态推进：以「指派前有没有生效承办人」为准，而不是只看当前状态是不是 PENDING_ASSIGN。
        // 后者会漏掉「没有承办人、状态却是处理中」的数据（历史/演示数据容易长这样），
        // 表现为指派后状态纹丝不动，看起来像没指派成功。
        // DONE / CANCELLED 在方法入口已拦截，这里不会误推进终态。
        if (oldList.isEmpty() || "PENDING_ASSIGN".equals(c.getStatus())) {
            c.setStatus("ASSIGNED");
        }
        c.setUpdatedAt(now);

        // 指派时可一并设定 / 调整截止期限；deadlineTouched=false 表示不动它
        String deadlineText = null;
        if (req.isDeadlineTouched()) {
            LocalDateTime old = c.getDeadline();
            c.setDeadline(req.getDeadline());
            deadlineText = req.getDeadline() == null
                    ? "清除截止期限"
                    : "截止期限设为 " + req.getDeadline().format(FMT)
                      + (old == null ? "" : "（原 " + old.format(FMT) + "）");
        }
        caseMapper.updateById(c);
        // updateById 会跳过 null 字段，「清空期限」必须显式写 null
        if (req.isDeadlineTouched() && req.getDeadline() == null) {
            caseMapper.update(null, new LambdaUpdateWrapper<CaseInfo>()
                    .eq(CaseInfo::getId, c.getId())
                    .set(CaseInfo::getDeadline, null));
        }

        // 指派时可一并提交待办清单（todos=null 表示不改动既有待办）
        if (req.getTodos() != null) {
            todoService.syncForAssign(c.getId(), req.getTodos());
        }

        Map<Long, OrgEmployee> empMap = employeeService.employeeMap();
        StringBuilder sb = new StringBuilder("指派给：");
        for (Long empId : ordered) {
            OrgEmployee e = empMap.get(empId);
            sb.append(e == null ? empId : e.getName()).append(" ");
        }
        if (deadlineText != null) {
            sb.append("；").append(deadlineText);
        }
        if (req.getTodos() != null) {
            sb.append("；待办 ").append(todoService.countsOf(c.getId())[1]).append(" 项");
        }
        logService.log("CASE", "ASSIGN", "CASE", c.getId(), sb.toString(),
                beforeSnapshot, snapshotService.capture(c.getId()));
        return detail(c.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public CaseVO changeStatus(Long id, StatusRequest req) {
        CaseInfo c = caseMapper.selectById(id);
        if (c == null) {
            throw new BizException("案件不存在");
        }
        if (!VALID_STATUS.contains(req.getStatus())) {
            throw new BizException("非法的案件状态：" + req.getStatus());
        }
        // 办结 / 撤销 = 结案动作，属管理层权限；普通民警只能推进「处理中」
        if (MANAGER_ONLY_STATUS.contains(req.getStatus()) && !AuthContext.isFullAccess()) {
            throw new BizException(403, "当前角色（" + Roles.name(AuthContext.role()) + "）无权执行："
                    + DictHolder.name("STATUS", req.getStatus()) + "。如需开通请联系所长或法制员。");
        }
        // 进入「办理中 / 已办结」必须有人承办：否则会留下「无人承办却已办结」这类查不到责任人的记录
        if (NEED_ASSIGNEE_STATUS.contains(req.getStatus())) {
            Long activeCount = assigneeMapper.selectCount(new LambdaQueryWrapper<CaseAssignee>()
                    .eq(CaseAssignee::getCaseId, id)
                    .eq(CaseAssignee::getStatus, "ACTIVE"));
            if (activeCount == null || activeCount == 0L) {
                throw new BizException("该案件尚未指派承办人，不能变更为「"
                        + DictHolder.name("STATUS", req.getStatus()) + "」。请先指派承办人。");
            }
        }
        String beforeSnapshot = snapshotService.capture(id);
        c.setStatus(req.getStatus());
        if (StringUtils.hasText(req.getRemark())) {
            c.setRemark(req.getRemark());
        }
        c.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(c);
        logService.log("CASE", "STATUS", "CASE", id,
                "状态变更为：" + DictHolder.name("STATUS", req.getStatus()),
                beforeSnapshot, snapshotService.capture(id));
        return detail(id);
    }

    // ------------------------------------------------------------------
    // 到期提醒 / 工作台
    // ------------------------------------------------------------------

    /**
     * 到期提醒列表。
     *
     * <p>caseType 由统一类型选择器注入（2026-10）：内部走 {@link #page}，
     * 所以过滤口径与案件管理页完全一致，不会出现两页数字不同。
     */
    public List<CaseVO> reminders(String bucket, int limit, String caseType) {
        CaseQuery q = new CaseQuery();
        q.setPage(1);
        q.setSize(limit);
        q.setDueBucket(bucket);
        q.setSortField("deadline");
        q.setSortOrder("asc");
        q.setStatus("OPEN");
        q.setCaseType(caseType);
        return page(q).getList();
    }

    public List<CaseVO> reminders(String bucket, int limit) {
        return reminders(bucket, limit, null);
    }

    public DashboardVO dashboard() {
        return dashboard(null);
    }

    /**
     * 工作台数据。
     *
     * @param onlyMine 只看本人名下案件。管理层传 true 也会收敛到本人（「我的案件」页复用同一口径）；
     *                 普通民警无论传什么都被强制收敛，防止直接调接口拉全所数据。
     */
    public DashboardVO dashboard(Boolean onlyMine) {
        if (!AuthContext.isFullAccess() || Boolean.TRUE.equals(onlyMine)) {
            return personalDashboard();
        }
        DashboardVO vo = new DashboardVO();
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        vo.setTotalCase(caseMapper.selectCount(null));
        vo.setPendingAssign(caseMapper.selectCount(new LambdaQueryWrapper<CaseInfo>().eq(CaseInfo::getStatus, "PENDING_ASSIGN")));
        vo.setInProgress(caseMapper.selectCount(new LambdaQueryWrapper<CaseInfo>().eq(CaseInfo::getStatus, "IN_PROGRESS")));
        vo.setDone(caseMapper.selectCount(new LambdaQueryWrapper<CaseInfo>().eq(CaseInfo::getStatus, "DONE")));
        vo.setOpenCase(caseMapper.selectCount(new LambdaQueryWrapper<CaseInfo>()
                .notIn(CaseInfo::getStatus, new ArrayList<>(java.util.Arrays.asList("DONE", "CANCELLED")))));
        vo.setEmployeeCount(employeeService.employeeMap().size());

        LambdaQueryWrapper<CaseInfo> overdue = new LambdaQueryWrapper<CaseInfo>()
                .isNotNull(CaseInfo::getDeadline).lt(CaseInfo::getDeadline, now)
                .notIn(CaseInfo::getStatus, new ArrayList<>(java.util.Arrays.asList("DONE", "CANCELLED")));
        vo.setOverdue(caseMapper.selectCount(overdue));
        vo.setDueToday(caseMapper.selectCount(new LambdaQueryWrapper<CaseInfo>()
                .isNotNull(CaseInfo::getDeadline)
                .ge(CaseInfo::getDeadline, today.atStartOfDay())
                .lt(CaseInfo::getDeadline, today.plusDays(1).atStartOfDay())
                .notIn(CaseInfo::getStatus, new ArrayList<>(java.util.Arrays.asList("DONE", "CANCELLED")))));
        vo.setDueIn3Days(caseMapper.selectCount(new LambdaQueryWrapper<CaseInfo>()
                .isNotNull(CaseInfo::getDeadline).ge(CaseInfo::getDeadline, now)
                .lt(CaseInfo::getDeadline, today.plusDays(4).atStartOfDay())
                .notIn(CaseInfo::getStatus, new ArrayList<>(java.util.Arrays.asList("DONE", "CANCELLED")))));
        vo.setDueIn7Days(caseMapper.selectCount(new LambdaQueryWrapper<CaseInfo>()
                .isNotNull(CaseInfo::getDeadline).ge(CaseInfo::getDeadline, now)
                .lt(CaseInfo::getDeadline, today.plusDays(8).atStartOfDay())
                .notIn(CaseInfo::getStatus, new ArrayList<>(java.util.Arrays.asList("DONE", "CANCELLED")))));

        vo.setOverdueList(reminders("OVERDUE", 10));
        vo.setDueSoonList(reminders("D7", 10));
        vo.setPendingList(pendingList(8));
        vo.setRecentLogs(operationLogService.recent(20));
        return vo;
    }

    private List<CaseVO> pendingList(int limit) {
        CaseQuery q = new CaseQuery();
        q.setPage(1);
        q.setSize(limit);
        q.setStatus("PENDING_ASSIGN");
        return page(q).getList();
    }

    /**
     * 普通民警的看板口径：所有计数只统计本人名下案件，不暴露全所数据。
     * 最近操作日志属于他人行为记录，普通民警一律不给（返回空表）。
     */
    private DashboardVO personalDashboard() {
        DashboardVO vo = new DashboardVO();
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        Set<Long> mine = myVisibleCaseIds();
        List<CaseInfo> all = mine.isEmpty()
                ? Collections.<CaseInfo>emptyList()
                : caseMapper.selectBatchIds(mine);
        List<CaseInfo> open = all.stream()
                .filter(c -> !"DONE".equals(c.getStatus()) && !"CANCELLED".equals(c.getStatus()))
                .collect(Collectors.toList());

        vo.setTotalCase(all.size());
        vo.setPendingAssign(countStatus(all, "PENDING_ASSIGN"));
        vo.setInProgress(countStatus(all, "IN_PROGRESS"));
        vo.setDone(countStatus(all, "DONE"));
        vo.setOpenCase(open.size());
        vo.setEmployeeCount(1); // 站在普通民警视角，「警力」就是他自己
        vo.setOverdue(countDue(open, c -> c.getDeadline().isBefore(now)));
        vo.setDueToday(countDue(open, c -> !c.getDeadline().isBefore(today.atStartOfDay())
                && c.getDeadline().isBefore(today.plusDays(1).atStartOfDay())));
        vo.setDueIn3Days(countDue(open, c -> !c.getDeadline().isBefore(now)
                && c.getDeadline().isBefore(today.plusDays(4).atStartOfDay())));
        vo.setDueIn7Days(countDue(open, c -> !c.getDeadline().isBefore(now)
                && c.getDeadline().isBefore(today.plusDays(8).atStartOfDay())));

        // 三个清单走 page()，本身已按角色收敛，这里不用再筛
        vo.setOverdueList(reminders("OVERDUE", 10));
        vo.setDueSoonList(reminders("D7", 10));
        vo.setPendingList(pendingList(8));
        vo.setRecentLogs(Collections.emptyList());
        return vo;
    }

    private long countStatus(List<CaseInfo> list, String status) {
        return list.stream().filter(c -> status.equals(c.getStatus())).count();
    }

    /** 在办案件里按「有期限且满足条件」计数（未设期限的不计入任何到期桶） */
    private long countDue(List<CaseInfo> open, java.util.function.Predicate<CaseInfo> cond) {
        return open.stream().filter(c -> c.getDeadline() != null && cond.test(c)).count();
    }

    /** 案件分类统计（工作台小图用） */
    public Map<String, Long> statusStats() {
        Map<String, Long> map = new HashMap<>();
        for (String s : new String[]{"PENDING_ASSIGN", "ASSIGNED", "IN_PROGRESS", "DONE", "CANCELLED"}) {
            map.put(s, caseMapper.selectCount(new LambdaQueryWrapper<CaseInfo>().eq(CaseInfo::getStatus, s)));
        }
        return map;
    }
}
