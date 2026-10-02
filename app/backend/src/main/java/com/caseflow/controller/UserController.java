package com.caseflow.controller;

import com.caseflow.common.Result;
import com.caseflow.dto.UserSaveRequest;
import com.caseflow.security.FullAccessOnly;
import com.caseflow.service.UserService;
import com.caseflow.vo.EmployeeVO;
import com.caseflow.vo.UserVO;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号管理：注册审核、角色分配、启用停用、重置密码。
 *
 * <p>整个 Controller 都标注了 {@link FullAccessOnly}，只有所长 / 副所长 / 法制员 / 系统管理员能访问，
 * 普通民警调用会收到 403。
 */
@RestController
@RequestMapping("/users")
@FullAccessOnly("账号管理")
public class UserController {

    @Resource
    private UserService userService;

    /** 账号列表；auditStatus=0 只看待审核，keyword 支持登录名/姓名/手机号 */
    @GetMapping
    public Result<List<UserVO>> list(@RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) Integer auditStatus) {
        return Result.ok(userService.list(keyword, auditStatus));
    }

    /** 待审核数量，导航红点用 */
    @GetMapping("/pending-count")
    public Result<Map<String, Object>> pendingCount() {
        Map<String, Object> m = new HashMap<>();
        m.put("count", userService.pendingCount());
        return Result.ok(m);
    }

    /**
     * 「关联员工」下拉的候选名单：只返回还没被其他账号占用的员工。
     * 编辑账号时传 excludeUserId=当前账号，免得自己已绑的那个人被过滤掉。
     */
    @GetMapping("/bindable-employees")
    public Result<List<EmployeeVO>> bindableEmployees(@RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) Integer limit,
                                                      @RequestParam(required = false) Long excludeUserId) {
        return Result.ok(userService.bindableEmployees(keyword, limit, excludeUserId));
    }

    /** 审核通过（可同时确认最终角色） */
    @PostMapping("/{id}/approve")
    public Result<UserVO> approve(@PathVariable Long id, @RequestBody UserSaveRequest req) {
        return Result.ok(userService.approve(id, req));
    }

    /** 驳回注册申请 */
    @PostMapping("/{id}/reject")
    public Result<UserVO> reject(@PathVariable Long id, @RequestBody UserSaveRequest req) {
        return Result.ok(userService.reject(id, req));
    }

    /** 管理员直接建号（免审核） */
    @PostMapping
    public Result<UserVO> create(@RequestBody UserSaveRequest req) {
        return Result.ok(userService.create(req));
    }

    @PutMapping("/{id}")
    public Result<UserVO> update(@PathVariable Long id, @RequestBody UserSaveRequest req) {
        return Result.ok(userService.update(id, req));
    }

    /** 重置密码 */
    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestBody UserSaveRequest req) {
        userService.resetPassword(id, req.getPassword());
        return Result.ok();
    }

    /** 启用 / 停用 */
    @PostMapping("/{id}/status")
    public Result<UserVO> changeStatus(@PathVariable Long id, @RequestBody UserSaveRequest req) {
        return Result.ok(userService.changeStatus(id, req.getStatus()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return Result.ok();
    }
}
