package com.caseflow.controller;

import com.caseflow.common.Result;
import com.caseflow.entity.WorkLog;
import com.caseflow.service.WorkLogService;
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
import java.util.List;
import java.util.Map;

/**
 * 工作记录本（2026-10-29）。
 *
 * <p>权限：普通民警只能看/写自己的记录；其余角色（所长/副所长/队长/法制员/研判员/
 * 系统管理员）可检索查看任意民警的记录。具体收敛在 {@link WorkLogService}。
 */
@RestController
@RequestMapping("/work-logs")
public class WorkLogController {

    @Resource
    private WorkLogService workLogService;

    /** 列表：普通民警强制只看本人；管理侧可按 employeeId 筛选、按 keyword 检索 */
    @GetMapping
    public Result<List<WorkLog>> list(@RequestParam(required = false) Long employeeId,
                                      @RequestParam(required = false) String keyword) {
        return Result.ok(workLogService.list(employeeId, keyword));
    }

    /** 民警下拉（管理侧按人筛选用） */
    @GetMapping("/employees")
    public Result<List<Map<String, Object>>> employees() {
        return Result.ok(workLogService.employees());
    }

    /** 新增记录 */
    @PostMapping
    public Result<WorkLog> add(@RequestBody Map<String, Object> body) {
        return Result.ok(workLogService.add(
                longVal(body.get("employeeId")),
                strVal(body.get("content")),
                longVal(body.get("caseId")),
                strVal(body.get("caseName"))));
    }

    /** 修改记录 */
    @PutMapping("/{id}")
    public Result<WorkLog> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return Result.ok(workLogService.update(id,
                strVal(body.get("content")),
                longVal(body.get("caseId")),
                strVal(body.get("caseName"))));
    }

    /** 删除记录 */
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        workLogService.remove(id);
        return Result.ok();
    }

    private Long longVal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : Long.valueOf(s);
    }

    private String strVal(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
