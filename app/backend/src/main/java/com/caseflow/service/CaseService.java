package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.alibaba.excel.EasyExcel;
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
import com.caseflow.excel.CaseExcelListener;
import com.caseflow.excel.CaseExcelRow;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.SysUserMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.security.Roles;
import com.caseflow.support.DayDate;
import com.caseflow.support.DictHolder;
import com.caseflow.support.LogService;
import com.caseflow.vo.AssigneeVO;
import com.caseflow.vo.CaseVO;
import com.caseflow.vo.DashboardVO;
import com.caseflow.vo.ImportResultVO;
import com.caseflow.vo.SuspectVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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
    /** 期限按天展示用的格式（只到日期，不带时分） */
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 是否已进入「提前提醒期」。
     *
     * <p>口径：设了提前天数、且距期限不足该天数（含已逾期）即算提醒中；
     * 没设提前天数（NULL / 0）或没设期限，一律不算——
     * 用户没要求提醒就别打扰他。
     */
    private boolean isReminding(CaseInfo c, LocalDateTime now) {
        if (c.getDeadline() == null || c.getRemindDays() == null || c.getRemindDays() <= 0) {
            return false;
        }
        Integer left = DictHolder.daysLeft(c.getDeadline(), now);
        return left != null && left <= c.getRemindDays();
    }

    /**
     * 期限归一到「当天 23:59:59」。
     *
     * <p>为什么必须归一：期限现在按天选，若原样存 00:00:00，
     * 一进当天 00:00 就小于此刻，当天整天都会被判成「已逾期」。
     * 只动「时间正好是 00:00:00」的值——历史数据里的 18:00:00 原样保留。
     */
    private LocalDateTime normalizeDeadline(LocalDateTime deadline) {
        // 统一口径见 support/DayDate：按天选的值补成当天 23:59:59，带时分的原样保留
        return DayDate.endOfDayIfMidnight(deadline);
    }

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
    @Resource
    private TimeNodeService timeNodeService;
    /** 删案件时连信箱里的信件一起清（否则「查看案件」点进去是条死链） */
    @Resource
    private NotificationService notificationService;
    /** 「我的案件」口径的实现（依赖链末端）；对外仍走 {@link #myVisibleCaseIds()} */
    @Resource
    private CaseScopeService caseScopeService;
    /** 工作台「待审批的措施变更」计数（2026-10-24）；直接读 mapper，不依赖 ApprovalService，避免循环依赖 */
    @Resource
    private com.caseflow.mapper.CaseApprovalMapper approvalMapper;
    /**
     * 「提意见即派待办」的唯一落点。指派 / 建案要一并录入待办时也走它，
     * 保证待办只有一种产生方式（带 opinion_id、截止时间、重要程度、常用待办记忆）。
     */
    @Resource
    private OpinionService opinionService;
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

        // 嫌疑人一次批量取完（含姓名），避免列表里逐行查库
        Map<Long, List<CaseSuspect>> suspects = suspectsOf(result.getRecords().stream()
                .map(CaseInfo::getId).collect(Collectors.toList()));
        List<CaseVO> list = result.getRecords().stream()
                .map(c -> toVO(c, false, empMap, pathMap, suspects))
                .collect(Collectors.toList());
        return new PageResult<>(list, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 与案件列表共用筛选链，供已办结栏目图表限定同一批案件。 */
    public Set<Long> matchingCaseIds(CaseQuery query) {
        return caseMapper.selectList(buildWrapper(query).select(CaseInfo::getId)).stream()
                .map(CaseInfo::getId).collect(Collectors.toSet());
    }

    /**
     * 当前登录人「名下案件」的 id 集合，是列表 / 提醒 / 看板 / 图表的统一口径，
     * 避免各处各写一套筛选而出现口径不一致。
     *
     * <p><b>本方法是唯一对外门面</b>，实现已下沉到 {@link CaseScopeService}
     * （2026-10-13）——因为「指派 / 建案时一并录入待办」要求本服务反过来调用
     * {@code OpinionService}，而 {@code OpinionService} / {@code StaffTodoService}
     * 都要用「我的案件」口径。若它们仍注入本服务，依赖就成了环。
     * 抽离后依赖链是单向的：
     * <pre>
     * CaseService -&gt; OpinionService / StaffTodoService -&gt; CaseScopeService -&gt; Mapper
     * </pre>
     * 新增「按人取案件」的功能请继续调本方法，不要另写一套。
     */
    public Set<Long> myVisibleCaseIds() {
        return caseScopeService.myVisibleCaseIds();
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
        // 「只看重点」：列表页的星标筛选。
        // focus 列带 NOT NULL DEFAULT 0，但存量行可能是 NULL，所以按「等于 1」筛，
        // 不写成 <> 0 / isNull，免得把历史数据算进重点里。
        if (Boolean.TRUE.equals(query.getFocusOnly())) {
            q.eq(CaseInfo::getFocus, 1);
        }
        if (StringUtils.hasText(query.getCategory())) {
            if ("NONE".equalsIgnoreCase(query.getCategory().trim())) {
                // 「按类别浏览」板块页的「未分类」入口：类别为空（NULL 或空串）的案件。
                // 与 OTHER 类型的 NOT IN 口径同理——等值匹配会把空值存量漏掉。
                q.and(w -> w.isNull(CaseInfo::getCategory).or().eq(CaseInfo::getCategory, ""));
            } else {
                q.eq(CaseInfo::getCategory, query.getCategory());
            }
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
            // 措施 → 子模块的映射统一走 PoliceGroup.measuresOfModule：
            // 2026-10-09 加了「拘传 / 逮捕」后，如果这里还写死 DETENTION、BAIL/RESIDENCE，
            // 新措施会一个模块都落不进去（列表里凭空消失）。
            List<String> measures = com.caseflow.flow.PoliceGroup.measuresOfModule(m);
            if (!measures.isEmpty()) {
                if ("INITIAL".equals(m)) {
                    // 初查：无措施（含存量 NULL）+ 拘传这类"不改变在押状态"的措施 + 在办
                    if (!"ADMINISTRATIVE".equals(query.getCaseType())) {
                        q.and(w -> w.isNull(CaseInfo::getCaseMeasure)
                                .or().eq(CaseInfo::getCaseMeasure, "NONE")
                                .or().in(CaseInfo::getCaseMeasure, measures));
                    }
                    q.notIn(CaseInfo::getStatus, new ArrayList<>(java.util.Arrays.asList("DONE", "CANCELLED")));
                } else {
                    q.in(CaseInfo::getCaseMeasure, measures)
                            .notIn(CaseInfo::getStatus, new ArrayList<>(java.util.Arrays.asList("DONE", "CANCELLED")));
                }
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
        // 只看重点关注（2026-10-09）：列表上的星标要能反过来把标过的案件筛出来
        if (Boolean.TRUE.equals(query.getFocusOnly())) {
            q.eq(CaseInfo::getFocus, 1);
        }

        String field = query.getSortField() == null ? "case_time" : query.getSortField();
        boolean asc = "asc".equalsIgnoreCase(query.getSortOrder());
        switch (field) {
            case "case_time":
                // 案件时间（2026-10-18）：列表默认排序。同一时刻的案件再按创建时间兜一层，
                // 保证分页翻页时顺序稳定（否则同值行在页间会来回跳）
                q.orderBy(true, asc, CaseInfo::getCaseTime);
                q.orderBy(true, false, CaseInfo::getCreatedAt);
                break;
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
                        Map<Long, List<CaseSuspect>> suspectMap) {
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
        // 期限只按天展示（2026-10-09）：时间统一归一到当天 23:59:59，再显示时分没有意义
        vo.setDeadlineText(c.getDeadline() == null ? null : c.getDeadline().format(DAY_FMT));
        LocalDateTime now = LocalDateTime.now();
        vo.setDaysLeft(DictHolder.daysLeft(c.getDeadline(), now));
        vo.setDueLevel(DictHolder.dueLevel(c.getDeadline(), now));
        // ---- 期限节点名称 / 提前提醒 / 重点关注（2026-10-09）----
        vo.setDeadlineLabel(c.getDeadlineLabel());
        vo.setRemindDays(c.getRemindDays());
        vo.setFocus(c.getFocus() == null ? 0 : c.getFocus());
        vo.setReminding(isReminding(c, now));
        vo.setStatus(c.getStatus());
        vo.setStatusName(DictHolder.name("STATUS", c.getStatus()));
        // ---- 盯办字段：强制措施 / 侦查进度 / 措施期限倒计时 ----
        vo.setCaseMeasure(c.getCaseMeasure());
        vo.setCaseMeasureName(DictHolder.name("CASE_MEASURE", c.getCaseMeasure()));
        vo.setMeasureDate(c.getMeasureDate());
        vo.setDetainDeadline(c.getDetainDeadline());
        vo.setDetainDeadlineText(c.getDetainDeadline() == null ? null : DayDate.text(c.getDetainDeadline()));
        vo.setDetainDaysLeft(DictHolder.daysLeft(c.getDetainDeadline(), now));
        vo.setInvestigationStatus(c.getInvestigationStatus());
        vo.setInvestigationStatusName(DictHolder.name("INVESTIGATION_STATUS", c.getInvestigationStatus()));
        // 办案阶段（2026-10-12）：flow_stage 为 NULL 的存量案件按「初查」归位，
        // 与 FlowService.stageOf 同一口径，不在这里另判一次。
        String stage = c.getFlowStage() == null || c.getFlowStage().trim().isEmpty()
                ? com.caseflow.flow.CaseFlowTemplate.STAGE_INITIAL
                : c.getFlowStage();
        if ("ADMINISTRATIVE".equals(c.getCaseType())
                && !com.caseflow.flow.CaseFlowTemplate.STAGE_CLOSED.equals(stage)) {
            stage = com.caseflow.flow.CaseFlowTemplate.STAGE_INITIAL;
        }
        vo.setFlowStage(stage);
        vo.setFlowStageName(com.caseflow.flow.CaseFlowTemplate.stageLabel(stage));
        vo.setRemark(c.getRemark());
        // Excel 导入字段（2026-10-14）：原样透出，前端详情页展示；列表不带（一列塞不下）
        vo.setOccurPlace(c.getOccurPlace());
        vo.setOccurTime(c.getOccurTime());
        vo.setUndertakeUnit(c.getUndertakeUnit());
        vo.setAcceptTime(c.getAcceptTime());
        vo.setCaseTime(c.getCaseTime());
        vo.setPunishTime(c.getPunishTime());
        vo.setExternalId(c.getExternalId());
        vo.setCreatedByName(userName(c.getCreatedBy()));
        vo.setCreatedAt(c.getCreatedAt());
        vo.setUpdatedAt(c.getUpdatedAt());

        List<CaseSuspect> sp = suspectMap == null ? null : suspectMap.get(c.getId());
        vo.setSuspectCount(sp == null ? 0 : sp.size());
        // 列表模式也带上嫌疑人姓名——否则列表只能显示人数，看不出是谁（2026-10-09）。
        // 只回 id / 姓名 / 性别：列表用不到身份证号、手机号，不送到前端（最小暴露）；
        // full=true（详情）时下面会用完整 VO 覆盖掉。
        vo.setSuspects(sp == null ? new ArrayList<>() : sp.stream()
                .map(this::toBriefSuspect).collect(Collectors.toList()));

        List<AssigneeVO> all = assigneesOf(c.getId(), empMap, pathMap);
        vo.setOwner(all.stream().filter(a -> "OWNER".equals(a.getAssignRole()) && "ACTIVE".equals(a.getStatus()))
                .findFirst().orElse(null));
        vo.setMembers(all.stream().filter(a -> !"OWNER".equals(a.getAssignRole()) && "ACTIVE".equals(a.getStatus()))
                .collect(Collectors.toList()));
        if (full) {
            vo.setAssignHistory(all);
            vo.setFiles(fileService.filesOf(c.getId()));
            // 详情模式的嫌疑人由 suspectService 单独取（完整字段），
            // 计数必须跟着这份列表走——上面那句是列表模式的批量查询结果，
            // 在详情模式下 suspectMap 为 null，若不同步这里，
            // 详情会显示「嫌疑人 0 人」却列出两条姓名，自相矛盾。
            List<SuspectVO> fullSuspects = suspectService.listOf(c.getId());
            vo.setSuspects(fullSuspects);
            vo.setSuspectCount(fullSuspects == null ? 0 : fullSuspects.size());
            // 重要时间节点（2026-10-13）：只在详情模式整份带出，
            // 列表模式不带——一列里塞不进多个节点，且列表只认主节点（三列已派生回写）
            vo.setTimeNodes(timeNodeService.listOf(c.getId()));
        }
        return vo;
    }

    private CaseVO toVO(CaseInfo c, boolean full, Map<Long, OrgEmployee> empMap, Map<Long, String> pathMap) {
        // full=true 时嫌疑人由 suspectService.listOf 单独取（完整字段），这里不必再查一次
        Map<Long, List<CaseSuspect>> suspects = full
                ? null
                : suspectsOf(java.util.Collections.singletonList(c.getId()));
        return toVO(c, full, empMap, pathMap, suspects);
    }

    /** 列表行上的嫌疑人：只带列表要用的字段，不带身份证号/手机号 */
    private SuspectVO toBriefSuspect(CaseSuspect s) {
        SuspectVO vo = new SuspectVO();
        vo.setId(s.getId());
        vo.setCaseId(s.getCaseId());
        vo.setName(s.getName());
        vo.setGender(s.getGender());
        vo.setGenderName(DictHolder.name("GENDER", s.getGender()));
        return vo;
    }

    /**
     * 批量取各案件的嫌疑人（一次 IN 查询，避免 N+1）。
     * 按 id 升序，保证「首个嫌疑人」稳定 = 最先录入的那个，列表上的「张三等N人」不会每次刷新换人。
     */
    private Map<Long, List<CaseSuspect>> suspectsOf(List<Long> caseIds) {
        Map<Long, List<CaseSuspect>> map = new HashMap<>();
        if (caseIds == null || caseIds.isEmpty()) {
            return map;
        }
        List<CaseSuspect> list = suspectMapper.selectList(new LambdaQueryWrapper<CaseSuspect>()
                .in(CaseSuspect::getCaseId, caseIds)
                .orderByAsc(CaseSuspect::getId));
        for (CaseSuspect s : list) {
            map.computeIfAbsent(s.getCaseId(), k -> new ArrayList<>()).add(s);
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
          String pg = com.caseflow.flow.PoliceGroup.normalize(e == null ? null : e.getPoliceGroup());
          vo.setPoliceGroup(pg);
          vo.setPoliceGroupName(com.caseflow.flow.PoliceGroup.label(pg));
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
        // 案件时间（2026-10-18）：已受案的以受案时间为准（导入时写入 accept_time），
        // 未受案的手工警情取报案时间，两者都汇总到这里，列表只读这一列。
        // 编辑时前端没传 reportTime 就不动——updateById 跳过 null，不会把已确认的时间抹成"现在"
        if (isNew) {
            c.setCaseTime(req.getReportTime() != null ? req.getReportTime() : now);
        } else if (req.getReportTime() != null) {
            c.setCaseTime(req.getReportTime());
        }
        // 调解书（mediationNo）2026-10-09 起不再由建案表单填写：这里**干脆不写**，
        // 免得表单一去掉该字段、保存时就把历史值抹成 null。列保留只为看得见老数据。
        c.setDescription(req.getDescription());
        c.setPriority(StringUtils.hasText(req.getPriority()) ? req.getPriority() : "NORMAL");
        // 期限三列（2026-10-13起不再直接采信表单）：
        // 时间节点表是唯一真源，这里只把表单填的那一个**临时**写到案件表，
        // 待节点落库后由 TimeNodeService.syncPrimary 按「最近节点」派生回写。
        // 换句话说：多节点时以节点表为准，只填单个时值与原来完全一致。
        if (Boolean.TRUE.equals(req.getClearDeadline())) {
            // 前端已废弃这三项（期限改由时间节点面板管理）：置 null，
            // 由下面的 LambdaUpdateWrapper 显式写 null（updateById 会跳过 null）
            c.setDeadline(null);
            c.setDeadlineLabel(null);
            c.setRemindDays(null);
        } else {
            c.setDeadline(normalizeDeadline(req.getDeadline()));
            c.setDeadlineLabel(StringUtils.hasText(req.getDeadlineLabel()) ? req.getDeadlineLabel().trim() : null);
            c.setRemindDays(req.getRemindDays() != null && req.getRemindDays() > 0 ? req.getRemindDays() : null);
        }
        // 强制措施：建案表单的「是否采取强制措施」。
        // 只在前端明确传值时才写（否 = NONE → 存 null），留空表示不动——
        // 否则编辑案件会把盯办模块登记好的措施（含期限）抹掉。
        if (StringUtils.hasText(req.getCaseMeasure())) {
            c.setCaseMeasure("NONE".equalsIgnoreCase(req.getCaseMeasure().trim())
                    ? null : req.getCaseMeasure().trim().toUpperCase());
        }
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
        }
        c.setSourceType(StringUtils.hasText(sourceType) ? sourceType : "MANUAL");

        // 先落库拿到案件 id，再把来源附件绑定到案件——绑定必须放在 insert 之后：
        // 新建时 c.getId() 在 insert 前是 null，先绑定会因 MyBatis-Plus 跳过 null 字段而静默失效
        if (isNew) {
            caseMapper.insert(c);
        } else {
            caseMapper.updateById(c);
        }
        if (fileId != null) {
            fileService.bindToCase(fileId, c.getId());
        }
        // 表单改回「否」（NONE）时，措施相关的三个字段要显式清空：
        // updateById 会跳过 null 字段，不这么做库里会留着旧措施日期与届满日，
        // 界面上却显示"无措施"，前后自相矛盾。
        if (!isNew && existing != null && StringUtils.hasText(req.getCaseMeasure())
                && "NONE".equalsIgnoreCase(req.getCaseMeasure().trim())) {
            caseMapper.update(null, new LambdaUpdateWrapper<CaseInfo>()
                    .eq(CaseInfo::getId, c.getId())
                    .set(CaseInfo::getCaseMeasure, null)
                    .set(CaseInfo::getMeasureDate, null)
                    .set(CaseInfo::getDetainDeadline, null));
        }
        // 期限三列显式清空（2026-10-13）：同上，updateById 跳过 null，必须单独 update。
        // 节点表已是唯一真源，案件表留着旧期限会让列表与节点面板显示不一致。
        if (!isNew && Boolean.TRUE.equals(req.getClearDeadline())) {
            caseMapper.update(null, new LambdaUpdateWrapper<CaseInfo>()
                    .eq(CaseInfo::getId, c.getId())
                    .set(CaseInfo::getDeadline, null)
                    .set(CaseInfo::getDeadlineLabel, null)
                    .set(CaseInfo::getRemindDays, null));
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
        // 建案时一并录入嫌疑人与重要时间节点（2026-10-13）
        //
        // 两者都是**选填**：空数组直接跳过，不阻断建案。
        // 放在指派之后、快照之前——快照要把这些一起带上，
        // 否则撤回「建案」后案件还在、嫌疑人和节点却留在库里成了孤儿。
        if (req.getSuspects() != null && !req.getSuspects().isEmpty()) {
            suspectService.addAllQuietly(c.getId(), req.getSuspects());
        }
        if (req.getTimeNodes() != null && !req.getTimeNodes().isEmpty()) {
            timeNodeService.addAllQuietly(c.getId(), req.getTimeNodes());
        }
        // 待办同样选填（2026-10-13）。落库路径与案件详情「添加待办」一致：
        // 意见 → 派生待办，所以带截止时间、重要程度，也进「常用待办」记忆。
        //
        // 为什么排在快照之前：case_todo 已在快照范围内，但 case_leader_opinion
        // 也要一并进快照（见 CaseSnapshot#opinions），否则撤回建案后
        // 意见会残留成孤儿，而 StaffTodoService.backfillFromOpinions()
        // 可能把这些孤儿意见重新派生出待办，出现"案件没了但待办还在"。
        if (req.getTodos() != null && !req.getTodos().isEmpty()) {
            opinionService.addDraftsQuietly(c.getId(), req.getTodos());
        }
        // 建案不铺任何任务（2026-10-12）：阶段任务 = 案件待办，
        // 由领导提意见派生，不在系统里另造一套"标准任务清单"与待办并行。
        // 快照写在最后：这样 after 里连指派关系、嫌疑人、时间节点一起带上，
        // 撤回时能整体回到操作前
        String after = snapshotService.capture(c.getId());
        logService.log("CASE", isNew ? "CREATE" : "UPDATE", "CASE", c.getId(),
                (isNew ? "创建案件：" : "更新案件：") + c.getName(), beforeSnapshot, after);
        return detail(c.getId());
    }

    // ------------------------------------------------------------------
    // Excel 导入（2026-10-14）
    // ------------------------------------------------------------------

    /**
     * 案件 Excel 导入。字段清单（14 列）：
     * id / 案件编号 / 案件名称 / 发案地点 / 承办单位 / 主办人 / 案件状态 /
     * 嫌疑人身份证号 / 嫌疑人 / 受理时间 / 处罚时间 / 案件类型 / 发案时间 / 简要案情。
     *
     * <p><b>幂等键 = 案件编号（case_no）</b>：已存在则只更新基础信息字段，
     * 不重复指派、不重复录入嫌疑人；不存在才新建。编号为空时自动生成。
     *
     * <p><b>逐行容错</b>：单行写库失败只记 errors，不影响其他行——
     * 与员工图谱导入同一口径（能导的先进来，错误清单回头逐条修）。
     */
    public ImportResultVO importExcel(MultipartFile file) {
        ImportResultVO result = new ImportResultVO();
        List<String> errors = new ArrayList<>();
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要导入的 Excel 文件");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!original.endsWith(".xls") && !original.endsWith(".xlsx") && !original.endsWith(".csv")) {
            throw new BizException("仅支持 .xls / .xlsx / .csv 文件");
        }
        CaseExcelListener listener = new CaseExcelListener();
        try (InputStream in = file.getInputStream()) {
            EasyExcel.read(in, CaseExcelRow.class, listener).sheet().doRead();
        } catch (IOException e) {
            throw new BizException("文件读取失败：" + e.getMessage());
        }
        List<CaseExcelRow> rows = listener.getRows();
        result.setTotal(rows.size());
        if (rows.isEmpty()) {
            throw new BizException("未解析到任何有效数据行");
        }

        // 员工姓名 -> 档案列表。值是 List 而不是单个：重名要能发现，
        // 悄悄取第一个会把案件派错给同名的另一个人。
        Map<String, List<OrgEmployee>> employeesByName = new HashMap<>();
        for (OrgEmployee e : employeeService.employeeMap().values()) {
            if (StringUtils.hasText(e.getName())) {
                employeesByName.computeIfAbsent(e.getName().trim(), k -> new ArrayList<>()).add(e);
            }
        }
        // 已有案件编号 -> 案件（幂等键）
        Map<String, CaseInfo> byCaseNo = new HashMap<>();
        for (CaseInfo c : caseMapper.selectList(null)) {
            if (StringUtils.hasText(c.getCaseNo())) {
                byCaseNo.put(c.getCaseNo(), c);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int success = 0;
        for (int i = 0; i < rows.size(); i++) {
            int lineNo = i + 2; // 表头占第 1 行
            try {
                importOne(rows.get(i), lineNo, employeesByName, byCaseNo, now, errors);
                success++;
            } catch (Exception ex) {
                errors.add("第 " + lineNo + " 行导入失败：" + ex.getMessage());
            }
        }
        result.setSuccess(success);
        result.setFailed(rows.size() - success);
        result.setErrors(errors);
        logService.log("CASE", "IMPORT", "CASE", null,
                "导入案件：共 " + rows.size() + " 行，成功 " + success + " 行");
        return result;
    }

    /**
     * 导入单行。非致命问题（时间解析不了、主办人没匹配上……）只记 errors 不抛异常，
     * 保证「能导的先进来」；缺案件名称这类必填问题才抛，由调用方记为失败行。
     */
    private void importOne(CaseExcelRow row, int lineNo,
                           Map<String, List<OrgEmployee>> employeesByName,
                           Map<String, CaseInfo> byCaseNo, LocalDateTime now,
                           List<String> errors) {
        if (!StringUtils.hasText(row.getName())) {
            throw new BizException("缺少案件名称");
        }
        // ---- 案件类型：刑事/行政/初查；空或映射不上按「未分类」入库（OTHER 口径）----
        String caseType = mapCaseType(row.getCaseTypeText());
        if (!StringUtils.hasText(row.getCaseTypeText())) {
            errors.add("第 " + lineNo + " 行：未填案件类型，按「未分类」入库");
        } else if (caseType == null) {
            errors.add("第 " + lineNo + " 行：无法识别的案件类型「" + row.getCaseTypeText() + "」，按「未分类」入库");
        }
        // ---- 时间列：宽松解析，失败记 error 后按 null 入库 ----
        LocalDateTime occurTime = parseExcelTime(row.getOccurTime());
        if (StringUtils.hasText(row.getOccurTime()) && occurTime == null) {
            errors.add("第 " + lineNo + " 行：发案时间「" + row.getOccurTime() + "」无法解析，已留空");
        }
        LocalDateTime acceptTime = parseExcelTime(row.getAcceptTime());
        if (StringUtils.hasText(row.getAcceptTime()) && acceptTime == null) {
            errors.add("第 " + lineNo + " 行：受理时间「" + row.getAcceptTime() + "」无法解析，已留空");
        }
        LocalDateTime punishTime = parseExcelTime(row.getPunishTime());
        if (StringUtils.hasText(row.getPunishTime()) && punishTime == null) {
            errors.add("第 " + lineNo + " 行：处罚时间「" + row.getPunishTime() + "」无法解析，已留空");
        }
        // ---- 主办人：按姓名匹配员工档案；重名/查无此人都不指派，只记提示 ----
        Long ownerId = null;
        if (StringUtils.hasText(row.getOwnerName())) {
            List<OrgEmployee> hits = employeesByName.get(row.getOwnerName());
            if (hits == null || hits.isEmpty()) {
                errors.add("第 " + lineNo + " 行：员工档案中找不到「" + row.getOwnerName() + "」，未指派主办人");
            } else if (hits.size() > 1) {
                errors.add("第 " + lineNo + " 行：员工档案中「" + row.getOwnerName() + "」重名 " + hits.size()
                        + " 人，未自动指派，请导入后手动指派");
            } else {
                ownerId = hits.get(0).getId();
            }
        }

        CaseInfo existing = StringUtils.hasText(row.getCaseNo()) ? byCaseNo.get(row.getCaseNo()) : null;
        if (existing != null) {
            // 已存在：只更新基础信息。状态/指派/嫌疑人一律不碰——
            // 那些是办理过程中产生的数据，被一次导入覆盖会毁掉留痕。
            existing.setName(row.getName());
            if (caseType != null) {
                existing.setCaseType(caseType);
            }
            existing.setOccurPlace(row.getOccurPlace());
            existing.setOccurTime(occurTime);
            existing.setUndertakeUnit(row.getUndertakeUnit());
            existing.setAcceptTime(acceptTime);
            existing.setPunishTime(punishTime);
            existing.setExternalId(row.getId());
            if (StringUtils.hasText(row.getDescription())) {
                existing.setDescription(row.getDescription());
            }
            existing.setUpdatedAt(now);
            caseMapper.updateById(existing);
            errors.add("第 " + lineNo + " 行：案件编号「" + row.getCaseNo() + "」已存在，已更新基础信息"
                    + "（状态 / 指派 / 嫌疑人未动）");
            return;
        }

        // 新建。状态映射 + 无人承办降级（与 save() 同一套约束：
        // 在办/办结必须有人承办，已指派也必须有人，否则造出查不到责任人的记录）
        String status = mapStatus(row.getStatusText());
        if (StringUtils.hasText(row.getStatusText()) && status == null) {
            errors.add("第 " + lineNo + " 行：无法识别的案件状态「" + row.getStatusText() + "」，按「待指派」入库");
        }
        if (status == null) {
            status = "PENDING_ASSIGN";
        } else if (ownerId == null && (NEED_ASSIGNEE_STATUS.contains(status) || "ASSIGNED".equals(status))) {
            errors.add("第 " + lineNo + " 行：状态「" + row.getStatusText() + "」需要承办人，但未匹配到主办人，"
                    + "已按「待指派」入库");
            status = "PENDING_ASSIGN";
        }

        CaseInfo c = new CaseInfo();
        c.setCaseNo(StringUtils.hasText(row.getCaseNo()) ? row.getCaseNo() : generateCaseNo());
        c.setName(row.getName());
        c.setCaseType(caseType);
        c.setOccurPlace(row.getOccurPlace());
        c.setOccurTime(occurTime);
        c.setUndertakeUnit(row.getUndertakeUnit());
        c.setAcceptTime(acceptTime);
        // 案件时间（2026-10-18）：导入件一律以受案时间为准，缺失时退回导入时刻
        c.setCaseTime(acceptTime != null ? acceptTime : LocalDateTime.now());
        c.setPunishTime(punishTime);
        c.setExternalId(row.getId());
        c.setDescription(row.getDescription());
        c.setSourceType("EXCEL");
        c.setPriority("NORMAL");
        c.setStatus(status);
        c.setCreatedBy(AuthContext.userId());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        caseMapper.insert(c);
        byCaseNo.put(c.getCaseNo(), c);

        // 主办人 -> 指派关系（OWNER）。放在 insert 之后：拿得到案件 id
        if (ownerId != null) {
            CaseAssignee a = new CaseAssignee();
            a.setCaseId(c.getId());
            a.setEmployeeId(ownerId);
            a.setAssignRole("OWNER");
            a.setStatus("ACTIVE");
            a.setAssignedBy(AuthContext.userId());
            a.setAssignedAt(now);
            assigneeMapper.insert(a);
        }
        // 嫌疑人（单人列：姓名 + 身份证号）。只有姓名没有身份证号也录——
        // 身份证可以后补；只有身份证号没有姓名是废数据，不录。
        if (StringUtils.hasText(row.getSuspectName())) {
            CaseSuspect s = new CaseSuspect();
            s.setCaseId(c.getId());
            s.setName(row.getSuspectName());
            s.setIdCard(row.getSuspectIdCard());
            s.setCreatedBy(AuthContext.userId());
            s.setCreatedAt(now);
            s.setUpdatedAt(now);
            suspectMapper.insert(s);
        } else if (StringUtils.hasText(row.getSuspectIdCard())) {
            errors.add("第 " + lineNo + " 行：填了嫌疑人身份证号但姓名为空，未录入嫌疑人");
        }
    }

    /**
     * 案件类型中文映射：刑事 / 行政 / 初查（未立案）。
     * 其余值返回 null = 未分类（前端「其他案件」口径：NOT IN(刑事,行政) OR IS NULL）。
     */
    private String mapCaseType(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.trim();
        // 本系统自己导出的文件里躺着的可能是英文码，先按码认
        String up = s.toUpperCase();
        if ("CRIMINAL".equals(up) || "ADMINISTRATIVE".equals(up) || "PRELIMINARY".equals(up)) {
            return up;
        }
        if (s.contains("刑")) {
            return "CRIMINAL";
        }
        if (s.contains("行政")) {
            return "ADMINISTRATIVE";
        }
        if (s.contains("初查") || s.contains("未立案")) {
            return "PRELIMINARY";
        }
        return null;
    }

    /**
     * 案件状态中文宽松映射（导入用）。
     *
     * <p><b>否定词必须最先判</b>：「未办结」里同时含「未」和「办结」，
     * 若先匹配「办结」会把没办结的案件打成已办结——这是状态映射里最坑的一种错法。
     */
    private String mapStatus(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.trim();
        String up = s.toUpperCase();
        if (VALID_STATUS.contains(up)) {
            return up;
        }
        if (s.contains("未") || s.contains("待")) {
            // 否定/将来时：只可能在办或还没开始，绝不可能是终态
            if (s.contains("办理") || s.contains("在办") || s.contains("侦")) {
                return "IN_PROGRESS";
            }
            return "PENDING_ASSIGN";
        }
        if (s.contains("办结") || s.contains("结案")) {
            return "DONE";
        }
        if (s.contains("撤销") || s.contains("撤案")) {
            return "CANCELLED";
        }
        if (s.contains("办理") || s.contains("在办") || s.contains("侦查中") || s.contains("侦办")) {
            return "IN_PROGRESS";
        }
        if (s.contains("受理") || s.contains("指派")) {
            return "ASSIGNED";
        }
        return null;
    }

    /**
     * 时间列宽松解析：常见字符串格式 + Excel 序列号 + Date.toString 形态。
     * 解析不了返回 null，由调用方记 error 后按空入库（不阻断整行）。
     *
     * <p>pattern 一律用宽松模式（M/d/H/m 而不是 MM/dd/HH/mm）：
     * 源系统导出常出现 "2026/1/5 9:03" 这种不补零的写法。
     */
    private LocalDateTime parseExcelTime(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.trim().replace('T', ' ');
        // 纯日期 pattern（无时分秒）必须走 LocalDate.parse 再补 00:00——
        // LocalDateTime.parse 遇到 "2026-09-15" 这种没有时间段的串会直接抛异常
        // （实测坑：第一批烟测里三个纯日期全被误报「无法解析」）。
        for (String p : new String[]{"yyyy-M-d", "yyyy/M/d", "yyyyMMdd"}) {
            try {
                return LocalDate.parse(s, DateTimeFormatter.ofPattern(p)).atStartOfDay();
            } catch (Exception ignored) {
                // 换下一个 pattern
            }
        }
        for (String p : new String[]{"yyyy-M-d H:m:s", "yyyy-M-d H:m",
                "yyyy/M/d H:m:s", "yyyy/M/d H:m", "yyyyMMddHHmmss"}) {
            try {
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(p));
            } catch (Exception ignored) {
                // 换下一个 pattern
            }
        }
        // Excel 序列号（日期单元格被存成文本时常见，如 45000 / 45000.5），原点 1899-12-30
        if (s.matches("\\d+(\\.\\d+)?")) {
            try {
                double v = Double.parseDouble(s);
                if (v > 20000 && v < 80000) {
                    LocalDate base = LocalDate.of(1899, 12, 30).plusDays((long) v);
                    double frac = v - Math.floor(v);
                    return frac <= 0 ? base.atStartOfDay()
                            : base.atStartOfDay().plusSeconds((long) Math.round(frac * 86400));
                }
            } catch (Exception ignored) {
                // 序列号换算失败，走最后兜底
            }
        }
        // 最后兜底：java.util.Date#toString 形态（"Wed Jan 15 10:30:00 CST 2026"）
        try {
            java.util.Date d = new java.text.SimpleDateFormat(
                    "EEE MMM dd HH:mm:ss zzz yyyy", java.util.Locale.ENGLISH).parse(s);
            if (d != null) {
                return LocalDateTime.ofInstant(d.toInstant(), java.time.ZoneId.systemDefault());
            }
        } catch (Exception ignored) {
            // 放弃
        }
        return null;
    }

    /** 下载案件导入模板（14 列，带一行示例数据） */
    public void writeTemplate(HttpServletResponse response) throws IOException {
        List<CaseExcelRow> demo = new ArrayList<>(java.util.Arrays.asList(oneTemplateRow()));
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String name = URLEncoder.encode("案件导入模板", "UTF-8").replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename=" + name + ".xlsx");
        EasyExcel.write(response.getOutputStream(), CaseExcelRow.class).sheet("案件").doWrite(demo);
    }

    private CaseExcelRow oneTemplateRow() {
        CaseExcelRow r = new CaseExcelRow();
        r.setId("1001");
        r.setCaseNo("A20260115001");
        r.setName("张三盗窃案");
        r.setOccurPlace("XX市XX区XX路12号");
        r.setUndertakeUnit("XX分局刑事侦查大队");
        r.setOwnerName("张伟");
        r.setStatusText("办理中");
        r.setSuspectIdCard("110101199001011234");
        r.setSuspectName("张三");
        r.setAcceptTime("2026-01-15 09:30:00");
        r.setPunishTime("");
        r.setCaseTypeText("刑事");
        r.setOccurTime("2026-01-14 21:10:00");
        r.setDescription("嫌疑人潜入某小区储藏室实施盗窃，涉案财物价值约3000元。");
        return r;
    }

    // ------------------------------------------------------------------
    // 一键重点关注（2026-10-09）
    // ------------------------------------------------------------------

    /**
     * 标注 / 取消「重点关注」：列表里一键切换，不必点开案件详情。
     *
     * <p><b>为什么不做成可撤回</b>：它是标记类操作，再点一下星就能改回来；
     * 若写进 {@link com.caseflow.service.OperationLogService} 的 UNDOABLE 白名单，
     * 反而会占住「案件最新一步」，把真正需要撤的指派 / 期限改动挡在外面
     * （见 blockReason「该案件之后又有新操作」）。日志照写，只是不可撤。
     *
     * @param focus 1=重点关注，0=取消
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseVO focus(Long id, Integer focus) {
        CaseInfo c = caseMapper.selectById(id);
        if (c == null) {
            throw new BizException("案件不存在");
        }
        int next = focus == null || focus == 0 ? 0 : 1;
        int prev = c.getFocus() == null ? 0 : c.getFocus();
        if (prev == next) {
            return detail(id);
        }
        String before = snapshotService.capture(id);
        caseMapper.update(null, new LambdaUpdateWrapper<CaseInfo>()
                .eq(CaseInfo::getId, id)
                .set(CaseInfo::getFocus, next)
                .set(CaseInfo::getUpdatedAt, LocalDateTime.now()));
        logService.log("CASE", "FOCUS", "CASE", id,
                next == 1 ? "标注为重点关注：" + c.getName() : "取消重点关注：" + c.getName(),
                before, snapshotService.capture(id));
        return detail(id);
    }

    private String generateCaseNo() {
        String day = LocalDate.now().format(DAY);
        // 2026-10-18：不再用「CA-」前缀（该编号无业务含义，且列表已改为展示案件时间）。
        // 改成「日期-流水」，仅用于满足唯一约束与内部检索。
        String prefix = day + "-";
        // 取当天编号最大值 +1，而不是 count+1：删除案件后 count 变小会导致编号复用
        List<CaseInfo> latest = caseMapper.selectList(new LambdaQueryWrapper<CaseInfo>()
                .likeRight(CaseInfo::getCaseNo, prefix)
                .orderByDesc(CaseInfo::getCaseNo)
                .last("LIMIT 1"));
        int next = 1;
        if (!latest.isEmpty() && latest.get(0).getCaseNo() != null) {
            String no = latest.get(0).getCaseNo();
            try {
                next = Integer.parseInt(no.substring(prefix.length())) + 1;
            } catch (Exception ignored) {
                // 历史编号格式异常时退回 1，最多与旧编号撞号由唯一约束兜底
            }
        }
        return prefix + String.format("%03d", next);
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
        // 信箱里指向本案的信件一并清掉：案件没了，那些「查看案件」就成了点不开的死链
        notificationService.removeAllOfCase(id);
        // 待审批的措施变更一并清掉（2026-10-24）：案件都没了，这条申请永远批不了，
        // 留着会让工作台的「待审批 N 条」永远挂着一条点不动的记录。
        // 只删 PENDING——已批/已退回是审批留痕，属于历史，不能删。
        approvalMapper.delete(new LambdaQueryWrapper<com.caseflow.entity.CaseApproval>()
                .eq(com.caseflow.entity.CaseApproval::getCaseId, id)
                .eq(com.caseflow.entity.CaseApproval::getResult, "PENDING"));
        caseMapper.deleteById(id);
        logService.log("CASE", "DELETE", "CASE", id, "删除案件：" + c.getName(),
                before, snapshotService.absentJson());
    }

    // ------------------------------------------------------------------
    // 指派 / 改派
    // ------------------------------------------------------------------

    /**
     * 推导该案件要求什么办案组别。
     *
     * <p>依据是强制措施：刑拘在办的案件必然已登记 DETENTION，此时该由清案组接手；
     * 其余（未采取措施 / 取保监居 / 行政 / 未立案）不限制组别。
     *
     * <p>推导口径与列表页 module 参数的过滤逻辑保持一致（见 page() 里的 module 分支），
     * 否则会出现「在刑拘在办页签里选人，却被提示初查组限制」的错觉。
     */
    private String requiredGroupOf(CaseInfo c) {
        return com.caseflow.flow.PoliceGroup.requiredOf(c.getCaseType(), moduleOf(c));
    }

    /** 案件当前所属盯办子模块：DETENTION刑拘在办 / INITIAL初查 / BAIL_RESIDENCE取保监居 */
    private String moduleOf(CaseInfo c) {
        return com.caseflow.flow.PoliceGroup.moduleOfMeasure(c.getCaseMeasure());
    }

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

        // 办案组别校验（2026-10-04，2026-10-07 修订）：初查任务只能派给初查组或不限，
        // 刑拘在办只能派给清案组或不限（不限 > 其他任何组），其他案件不限制。
        // 组别取自**员工档案** org_employee.police_group
        // （指派选的是员工，组别理应挂在档案上；账号上的组别只是注册时自报）。
        // 存量员工没填组别 = 「不限」，不会被拦——上线即瘫痪比放宽规则更糟。
        String required = requiredGroupOf(c);
        if (!com.caseflow.flow.PoliceGroup.NONE.equals(required)) {
            for (Long empId : target) {
                OrgEmployee e = employeeService.employeeMap().get(empId);
                String g = e.getPoliceGroup();
                if (!com.caseflow.flow.PoliceGroup.canTake(required, g)) {
                    throw new BizException("「" + com.caseflow.flow.PoliceGroup.label(required)
                            + "」案件只能指派给" + com.caseflow.flow.PoliceGroup.label(required)
                            + "或「不限」人员；" + e.getName() + " 属于「"
                            + com.caseflow.flow.PoliceGroup.label(g) + "」");
                }
            }
        }

        // 旧指派关系按目标集合对齐（改派留痕）：
        //   目标集合里的人 → 保留原关系行（避免重复插入破坏历史），角色与 ownerId 对齐；
        //   不在目标集合里的人 → 置 REPLACED 留痕。
        // 旧实现把所有旧行一律置 REPLACED、只重新激活主办人，结果「保留的协办人」会被整体踢掉；
        // 且协办转主办时只恢复状态不改角色，导致案件没有任何 OWNER。
        String beforeSnapshot = snapshotService.capture(c.getId());
        List<CaseAssignee> oldList = assigneeMapper.selectList(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getCaseId, c.getId()).eq(CaseAssignee::getStatus, "ACTIVE"));
        LocalDateTime now = LocalDateTime.now();

        Long ownerId = req.getOwnerId();
        if (ownerId == null) {
            for (CaseAssignee old : oldList) {
                if ("OWNER".equals(old.getAssignRole())) {
                    ownerId = old.getEmployeeId();
                    break;
                }
            }
        }
        if (ownerId == null && !target.isEmpty()) {
            ownerId = target.iterator().next();
        }
        // 主办人必须落在承办人集合里（前端一般会带上，这里兜底补齐）
        if (ownerId != null) {
            target.add(ownerId);
        }

        Set<Long> kept = new HashSet<>();
        for (CaseAssignee old : oldList) {
            if (!target.contains(old.getEmployeeId()) || !kept.add(old.getEmployeeId())) {
                // 不再承办（或同一员工的多余历史行）→ 置 REPLACED
                if (!"REPLACED".equals(old.getStatus())) {
                    old.setStatus("REPLACED");
                    old.setClosedAt(now);
                    assigneeMapper.updateById(old);
                }
                continue;
            }
            // 保留：状态保持 ACTIVE，角色按是否主办对齐
            String wantRole = old.getEmployeeId().equals(ownerId) ? "OWNER" : "MEMBER";
            boolean dirty = false;
            if (!wantRole.equals(old.getAssignRole())) {
                old.setAssignRole(wantRole);
                dirty = true;
            }
            if (!"ACTIVE".equals(old.getStatus())) {
                old.setStatus("ACTIVE");
                old.setClosedAt(null);
                dirty = true;
            }
            if (dirty) {
                assigneeMapper.updateById(old);
            }
        }

        // 新增的承办人（原关系里没有的）逐个插入
        List<Long> ordered = new ArrayList<>(target);
        java.util.Collections.sort(ordered);
        for (Long empId : ordered) {
            if (oldList.stream().anyMatch(o -> o.getEmployeeId().equals(empId))) {
                continue;
            }
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
            c.setDeadline(normalizeDeadline(req.getDeadline()));
            deadlineText = req.getDeadline() == null
                    ? "清除截止期限"
                    : "截止期限设为 " + DayDate.text(req.getDeadline())
                      + (old == null ? "" : "（原 " + DayDate.text(old) + "）");
        }
        caseMapper.updateById(c);
        // updateById 会跳过 null 字段，「清空期限」必须显式写 null
        if (req.isDeadlineTouched() && req.getDeadline() == null) {
            caseMapper.update(null, new LambdaUpdateWrapper<CaseInfo>()
                    .eq(CaseInfo::getId, c.getId())
                    .set(CaseInfo::getDeadline, null));
        }

        // 指派时可一并录入待办（todos=null 表示不添加）
        // 路径与案件详情「添加待办」一致：意见 → 派生待办，所以带截止时间与重要程度。
        // 放在指派关系写完之后：派生待办要按现职承办人算阶段与归属。
        int addedTodos = 0;
        if (req.getTodos() != null) {
            addedTodos = opinionService.addDraftsQuietly(c.getId(), req.getTodos()).size();
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
        if (addedTodos > 0) {
            sb.append("；一并添加待办 ").append(addedTodos).append(" 项");
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
        if ("ADMINISTRATIVE".equals(c.getCaseType()) && "CANCELLED".equals(req.getStatus())) {
            throw new BizException("行政案件仅支持已受案和已办结两种业务状态");
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
        return reminders(bucket, limit, caseType, false);
    }

    /**
     * 到期提醒清单。
     *
     * <p>内部走的还是 {@link #page}，所以「只看重点」等筛选口径与案件管理天然一致——
     * 不要在这里另写一套 deadline 查询，否则两个页面迟早对不上。
     *
     * @param focusOnly 只看重点关注（focus = 1）
     */
    public List<CaseVO> reminders(String bucket, int limit, String caseType, boolean focusOnly) {
        CaseQuery q = new CaseQuery();
        q.setPage(1);
        q.setSize(limit);
        q.setDueBucket(bucket);
        q.setSortField("deadline");
        q.setSortOrder("asc");
        q.setStatus("OPEN");
        q.setCaseType(caseType);
        q.setFocusOnly(focusOnly);
        return page(q).getList();
    }

    public List<CaseVO> reminders(String bucket, int limit) {
        return reminders(bucket, limit, null, false);
    }

    /**
     * 工作台「重点案件」清单（2026-10-12）。
     *
     * <p>仍然走 {@link #page}，不另写查询：
     * <ul>
     *   <li>focusOnly=true 与案件管理页的「只看重点」是**同一套 SQL 条件**，不会两页数字不同；</li>
     *   <li>数据范围收敛（{@link #scopeToSelf}）自动生效，普通民警只能看到自己的重点案件；</li>
     *   <li>VO 的 dueLevel / focus / 阶段名等派生字段一次算齐，前端不用再拼。</li>
     * </ul>
     *
     * <p>排序：按期限升序（越急越靠前）。**未设期限的排最后** —— 否则 NULL 在
     * 升序里落到最前，工作台第一行反倒是个"没期限"的案件，领导第一眼看过去最容易误判。
     * 这里在内存里二次排序，而不是依赖 SQL 的 NULLS LAST（H2 与 MySQL 默认行为不一致）。
     */
    public List<CaseVO> focusList(int limit) {
        CaseQuery q = new CaseQuery();
        q.setPage(1);
        q.setSize(limit);
        q.setStatus("OPEN");
        q.setFocusOnly(true);
        q.setSortField("deadline");
        q.setSortOrder("asc");
        List<CaseVO> list = new ArrayList<>(page(q).getList());
        list.sort(Comparator
                // 有期限的在前
                .comparing((CaseVO c) -> c.getDeadline() == null ? 1 : 0)
                // 同组内按期限升序（已逾期最前）；都无期限时按 id 兜底，保证顺序稳定
                .thenComparing(c -> c.getDeadline(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CaseVO::getId));
        return list;
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
        fillFocus(vo);
        fillMeasureAlerts(vo, null);
        return vo;
    }

    /**
     * 强制措施到期提醒（2026-10-24 用户要求：到期前在工作台总览提示）。
     *
     * <p>口径：案件未办结未撤销 + 已登记措施 + 有届满日。
     * <b>到期不等于清空措施</b>——措施是办案事实，到期只表示"该措施不再有效"，
     * 需由领导或承办人登记下一个措施才算真正结束，所以这里只算状态、不改库。
     *
     * @param scopeIds 数据范围（null = 全量，管理层）；非 null 时只统计这些案件
     */
    private void fillMeasureAlerts(DashboardVO vo, java.util.Collection<Long> scopeIds) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        if (scopeIds != null && scopeIds.isEmpty()) {
            vo.setMeasureAlerts(Collections.emptyList());
            vo.setMeasureExpired(0);
            vo.setMeasureExpiring(0);
            vo.setMeasurePending(0);
            return;
        }
        LambdaQueryWrapper<CaseInfo> q = new LambdaQueryWrapper<CaseInfo>()
                .isNotNull(CaseInfo::getCaseMeasure)
                .isNotNull(CaseInfo::getDetainDeadline)
                .notIn(CaseInfo::getStatus, new ArrayList<>(java.util.Arrays.asList("DONE", "CANCELLED")))
                .orderByAsc(CaseInfo::getDetainDeadline);
        if (scopeIds != null) {
            q.in(CaseInfo::getId, scopeIds);
        }
        List<CaseInfo> rows = caseMapper.selectList(q);
        List<com.caseflow.vo.MeasureAlertVO> alerts = new ArrayList<>();
        long expired = 0;
        long expiring = 0;
        for (CaseInfo c : rows) {
            com.caseflow.vo.MeasureAlertVO a = new com.caseflow.vo.MeasureAlertVO();
            a.setCaseId(c.getId());
            a.setCaseNo(c.getCaseNo());
            a.setCaseName(c.getName());
            a.setCaseType(c.getCaseType());
            a.setMeasure(c.getCaseMeasure());
            a.setMeasureName(com.caseflow.support.DictHolder.name("CASE_MEASURE", c.getCaseMeasure()));
            a.setMeasureDate(c.getMeasureDate());
            a.setDeadline(c.getDetainDeadline());
            // 按自然日算差：今天到期 = 0，昨天到期 = -1
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, c.getDetainDeadline().toLocalDate());
            a.setDaysLeft(days);
            a.setExpired(days < 0);
            if (a.isExpired()) {
                expired++;
            } else if (days <= 7) {
                expiring++;
            }
            // 只保留"已到期"与"7 天内到期"，其余还早的不占版面
            if (a.isExpired() || days <= 7) {
                alerts.add(a);
            }
        }
        // 已到期的排最前（按过期最久的在前），其余按届满日由近到远
        alerts.sort((x, y) -> {
            if (x.isExpired() != y.isExpired()) return x.isExpired() ? -1 : 1;
            return x.getDeadline().compareTo(y.getDeadline());
        });
        vo.setMeasureAlerts(alerts);
        vo.setMeasureExpired(expired);
        vo.setMeasureExpiring(expiring);
        // 待审批数：只有领导看得到审批入口，普通民警视角一律 0。
        // 必须排除"案件已删"的孤儿申请（inSql 与 pendingMeasureApprovalVOs 的过滤保持同口径），
        // 否则会出现"卡片写 2 条、点开只有 1 条"。
        vo.setMeasurePending(AuthContext.isFullAccess() && scopeIds == null
                ? approvalMapper.selectCount(new LambdaQueryWrapper<com.caseflow.entity.CaseApproval>()
                        .eq(com.caseflow.entity.CaseApproval::getApproveType, "MEASURE")
                        .eq(com.caseflow.entity.CaseApproval::getResult, "PENDING")
                        .inSql(com.caseflow.entity.CaseApproval::getCaseId, "SELECT id FROM case_info"))
                : 0);
    }

    /**
     * 填「重点案件」相关字段（KPI 计数 + 清单）。
     *
     * <p><b>计数必须等于清单的完整条数，不能等于清单截断后的长度</b>：
     * 清单只取前 N 条给人看，计数是"一共多少件重点案件"。
     * 若拿 list.size() 去填计数，案件一多卡片就会撒谎（显示 10 件，实际 30 件）。
     * 所以计数另走一次 count 查询，口径与 {@link #focusList} 的 focusOnly + OPEN 完全相同。
     *
     * <p>focusOverdue 用返回的清单算即可 —— 它的用途是"这 N 条里有几条已经逾期"，
     * 只对**页面上能看到的那几条**有意义，与计数语义不同，不复用计数。
     */
    private void fillFocus(DashboardVO vo) {
        vo.setFocusList(focusList(FOCUS_LIST_SIZE));
        CaseQuery cq = new CaseQuery();
        cq.setStatus("OPEN");
        cq.setFocusOnly(true);
        cq.setPage(1);
        cq.setSize(1);
        vo.setFocusCase(page(cq).getTotal());
        vo.setFocusOverdue(vo.getFocusList().stream()
                .filter(c -> "OVERDUE".equals(c.getDueLevel()))
                .count());
    }

    /** 工作台重点案件清单最多列几条（与其它两个清单同量级） */
    private static final int FOCUS_LIST_SIZE = 10;

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
        // 重点案件同样走 page()，scopeToSelf 会把范围收敛到本人名下，口径与上面三个清单一致
        fillFocus(vo);
        fillMeasureAlerts(vo, mine);
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
