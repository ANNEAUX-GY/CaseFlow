package com.caseflow.controller;

import com.caseflow.common.PageResult;
import com.caseflow.common.Result;
import com.caseflow.security.FullAccessOnly;
import com.caseflow.service.OperationLogService;
import com.caseflow.vo.LogVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * 操作日志：查看「这一步到底做了什么」，以及撤回（Ctrl+Z）。
 */
@RestController
@RequestMapping("/logs")
public class LogController {

    /** 注意字段名不能叫 logService——"@Resource 按名字找 Bean"，会撞上 support 包的 LogService */
    @Resource
    private OperationLogService operationLogService;

    /** 日志分页；module 可选（CASE / EMPLOYEE / FILE / AUTH）。操作日志是全所人员行为留痕，仅管理层可查 */
    @GetMapping
    @FullAccessOnly("查看操作日志")
    public Result<PageResult<LogVO>> page(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String module) {
        return Result.ok(operationLogService.page(page, size, module));
    }

    /** 最近 N 条（工作台面板）。仅管理层：普通民警的工作台本就不给 recentLogs */
    @GetMapping("/recent")
    @FullAccessOnly("查看操作日志")
    public Result<List<LogVO>> recent(@RequestParam(defaultValue = "20") int limit) {
        return Result.ok(operationLogService.recent(limit));
    }

    /**
     * 最近 N 条，按业务类型过滤（工作台「最近操作」页签用）。
     *
     * <p>type=case 案件相关（指派/增删改查/待办/材料）；type=other 其他操作（登录/注册/员工图谱）。
     * 过滤在数据库层做——登录记录产生频繁，取回再分类会把案件操作挤没。
     */
    @GetMapping("/recent-by-type")
    @FullAccessOnly("查看操作日志")
    public Result<List<LogVO>> recentByType(@RequestParam(defaultValue = "20") int limit,
                                           @RequestParam String type) {
        return Result.ok(operationLogService.recent(limit, type));
    }

    /** 单个案件的办理进度（详情抽屉时间线，按时间正序）。仅管理层：普通员工不展示办理进度 */
    @GetMapping("/case/{caseId}")
    @FullAccessOnly("查看办理进度")
    public Result<List<LogVO>> caseLogs(@PathVariable Long caseId) {
        return Result.ok(operationLogService.caseLogs(caseId));
    }

    /** 日志详情：含「字段 / 变更前 / 变更后」明细。仅管理层（与列表同一口径） */
    @GetMapping("/{id}")
    @FullAccessOnly("查看操作日志")
    public Result<LogVO> detail(@PathVariable Long id) {
        return Result.ok(operationLogService.detail(id));
    }

    /** 撤回指定操作 */
    @PostMapping("/{id}/undo")
    @FullAccessOnly("撤回操作")
    public Result<LogVO> undo(@PathVariable Long id) {
        return Result.ok(operationLogService.undo(id));
    }

    /** 撤回上一步（对应 Ctrl+Z） */
    @PostMapping("/undo-latest")
    @FullAccessOnly("撤回操作")
    public Result<LogVO> undoLatest() {
        return Result.ok(operationLogService.undoLatest());
    }
}
