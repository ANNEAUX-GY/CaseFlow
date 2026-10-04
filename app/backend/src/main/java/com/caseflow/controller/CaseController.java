package com.caseflow.controller;

import com.caseflow.common.PageResult;
import com.caseflow.common.Result;
import com.caseflow.dto.AssignRequest;
import com.caseflow.dto.CaseQuery;
import com.caseflow.dto.CaseSaveRequest;
import com.caseflow.dto.StatusRequest;
import com.caseflow.dto.SuspectSaveRequest;
import com.caseflow.security.AuthContext;
import com.caseflow.security.FullAccessOnly;
import com.caseflow.service.CaseService;
import com.caseflow.service.SuspectService;
import com.caseflow.vo.CaseVO;
import com.caseflow.vo.DashboardVO;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 案件：创建、列表、详情、指派、状态流转、到期提醒。
 */
@RestController
@RequestMapping("/cases")
public class CaseController {

    @Resource
    private CaseService caseService;
    @Resource
    private SuspectService suspectService;
    @Resource
    private com.caseflow.service.StatsService statsService;

    @GetMapping
    public Result<PageResult<CaseVO>> page(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String priority,
                                           @RequestParam(required = false) String sourceType,
                                           @RequestParam(required = false) String category,
                                           @RequestParam(required = false) String caseType,
                                           @RequestParam(required = false) String hasSuspect,
                                           @RequestParam(required = false) Long employeeId,
                                           @RequestParam(required = false) Boolean onlyMine,
                                           @RequestParam(required = false) String dueBucket,
                                           @RequestParam(required = false) String module,
                                           @RequestParam(required = false) String investigationStatus,
                                           @RequestParam(required = false) String suspectName,
                                           @RequestParam(required = false) String suspectIdCard,
                                           @RequestParam(required = false) String sortField,
                                           @RequestParam(required = false) String sortOrder) {
        CaseQuery q = new CaseQuery();
        q.setPage(page);
        q.setSize(size);
        q.setKeyword(keyword);
        q.setStatus(status);
        q.setPriority(priority);
        q.setSourceType(sourceType);
        q.setCategory(category);
        q.setCaseType(caseType);
        q.setHasSuspect(hasSuspect);
        q.setEmployeeId(employeeId);
        q.setOnlyMine(onlyMine != null && onlyMine);
        q.setDueBucket(dueBucket);
        q.setModule(module);
        q.setInvestigationStatus(investigationStatus);
        q.setSuspectName(suspectName);
        q.setSuspectIdCard(suspectIdCard);
        q.setSortField(sortField);
        q.setSortOrder(sortOrder);
        return Result.ok(caseService.page(q));
    }

    @GetMapping("/{id}")
    public Result<CaseVO> detail(@PathVariable Long id) {
        return Result.ok(caseService.detail(id));
    }

    @PostMapping
    public Result<CaseVO> create(@Validated @RequestBody CaseSaveRequest req) {
        return Result.ok(caseService.save(req));
    }

    @PutMapping("/{id}")
    public Result<CaseVO> update(@PathVariable Long id, @Validated @RequestBody CaseSaveRequest req) {
        req.setId(id);
        return Result.ok(caseService.save(req));
    }

    @DeleteMapping("/{id}")
    @FullAccessOnly("删除案件")
    public Result<Void> delete(@PathVariable Long id) {
        caseService.delete(id);
        return Result.ok();
    }

    /** 指派 / 改派 */
    @PostMapping("/{id}/assign")
    @FullAccessOnly("指派案件")
    public Result<CaseVO> assign(@PathVariable Long id, @RequestBody AssignRequest req) {
        req.setCaseId(id);
        return Result.ok(caseService.assign(req));
    }

    /** 状态流转 */
    @PostMapping("/{id}/status")
    public Result<CaseVO> changeStatus(@PathVariable Long id, @RequestBody StatusRequest req) {
        return Result.ok(caseService.changeStatus(id, req));
    }

    // ------------------------------------------------------------------
    // 嫌疑人（身份信息录入；增删改都带快照，可撤回）
    // ------------------------------------------------------------------

    @GetMapping("/{id}/suspects")
    public Result<List<com.caseflow.vo.SuspectVO>> suspects(@PathVariable Long id) {
        return Result.ok(suspectService.listOf(id));
    }

    @PostMapping("/{id}/suspects")
    public Result<List<com.caseflow.vo.SuspectVO>> addSuspect(@PathVariable Long id,
                                                              @RequestBody SuspectSaveRequest req) {
        req.setCaseId(id);
        return Result.ok(suspectService.add(req));
    }

    @PutMapping("/suspects/{suspectId}")
    public Result<List<com.caseflow.vo.SuspectVO>> updateSuspect(@PathVariable Long suspectId,
                                                                @RequestBody SuspectSaveRequest req) {
        req.setId(suspectId);
        return Result.ok(suspectService.update(req));
    }

    @DeleteMapping("/suspects/{suspectId}")
    public Result<List<com.caseflow.vo.SuspectVO>> removeSuspect(@PathVariable Long suspectId) {
        return Result.ok(suspectService.remove(suspectId));
    }

    /** 到期提醒清单：OVERDUE / TODAY / D3 / D7 / NONE
     *  caseType 由统一类型选择器注入（2026-10），内部走同一个 page() 查询，口径与案件管理一致 */
    @GetMapping("/reminders")
    public Result<List<CaseVO>> reminders(@RequestParam(defaultValue = "OVERDUE") String bucket,
                                          @RequestParam(defaultValue = "20") int limit,
                                          @RequestParam(required = false) String caseType) {
        return Result.ok(caseService.reminders(bucket, limit, caseType));
    }

    /**
     * 图表统计：一次返回各页面需要的全部口径（days 控制趋势与未来到期跨度）。
     * 可选多值筛选（逗号分隔）：caseTypes 案卷类型 / dueBuckets 到期桶 / priorities 优先级，
     * 维度间 AND、维度内 OR；不传则口径与旧版完全一致。
     *
     * <p>数据范围：管理层统计全量；普通民警只统计本人名下案件（由后端强制，前端传参覆盖不了）。
     */
    @GetMapping("/stats")
    public Result<com.caseflow.vo.StatsVO> stats(@RequestParam(required = false) Integer days,
                                                 @RequestParam(required = false) String caseTypes,
                                                 @RequestParam(required = false) String dueBuckets,
                                                 @RequestParam(required = false) String priorities) {
        // 非全权限角色（普通民警）恒收敛到本人名下案件，避免图表泄出全所数据
        Set<Long> restrict = AuthContext.isFullAccess() ? null : caseService.myVisibleCaseIds();
        return Result.ok(statsService.stats(days == null ? 14 : days,
                csvToSet(caseTypes), csvToSet(dueBuckets), csvToSet(priorities), restrict));
    }

    /** "CRIMINAL,ADMINISTRATIVE" -> Set；null/空返回 null 表示该维度不限 */
    private java.util.Set<String> csvToSet(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return null;
        }
        return new java.util.LinkedHashSet<>(Arrays.asList(csv.split(",")));
    }

    @GetMapping("/dashboard")
    public Result<DashboardVO> dashboard(@RequestParam(required = false) Boolean onlyMine) {
        return Result.ok(caseService.dashboard(onlyMine));
    }
}
