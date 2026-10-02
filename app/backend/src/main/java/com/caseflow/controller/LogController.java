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

    /** 日志分页；module 可选（CASE / EMPLOYEE / FILE / AUTH） */
    @GetMapping
    public Result<PageResult<LogVO>> page(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) String module) {
        return Result.ok(operationLogService.page(page, size, module));
    }

    /** 最近 N 条（工作台面板） */
    @GetMapping("/recent")
    public Result<List<LogVO>> recent(@RequestParam(defaultValue = "20") int limit) {
        return Result.ok(operationLogService.recent(limit));
    }

    /** 单个案件的办理进度（详情抽屉时间线，按时间正序） */
    @GetMapping("/case/{caseId}")
    public Result<List<LogVO>> caseLogs(@PathVariable Long caseId) {
        return Result.ok(operationLogService.caseLogs(caseId));
    }

    /** 日志详情：含「字段 / 变更前 / 变更后」明细 */
    @GetMapping("/{id}")
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
