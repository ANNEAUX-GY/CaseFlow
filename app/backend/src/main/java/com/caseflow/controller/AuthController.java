package com.caseflow.controller;

import com.caseflow.common.Result;
import com.caseflow.dto.LoginRequest;
import com.caseflow.dto.RegisterRequest;
import com.caseflow.security.AuthContext;
import com.caseflow.security.CurrentUser;
import com.caseflow.security.LoginInterceptor;
import com.caseflow.security.Roles;
import com.caseflow.service.AuthService;
import com.caseflow.service.EmployeeService;
import com.caseflow.support.DictHolder;
import com.caseflow.vo.EmployeeVO;
import com.caseflow.vo.LoginVO;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 登录 / 登出 / 字典。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private AuthService authService;

    @Resource
    private EmployeeService employeeService;

    @PostMapping("/login")
    public Result<LoginVO> login(@Validated @RequestBody LoginRequest req) {
        return Result.ok(authService.login(req));
    }

    /** 自助注册：提交后为待审核状态，需全权限角色审核通过才能登录 */
    @PostMapping("/register")
    public Result<Void> register(@Validated @RequestBody RegisterRequest req) {
        authService.register(req);
        return Result.ok();
    }

    /** 可选注册角色（注册页下拉用），不含系统管理员 */
    @GetMapping("/roles")
    public Result<Map<String, String>> roles() {
        return Result.ok(Roles.applicable());
    }

    /**
     * 注册页「认领员工档案」的可选名单。
     *
     * <p>放在 {@code /auth/**} 下是因为注册页还没有账号、拿不到令牌。
     * 因此只回传 id / 姓名 / 工号 / 部门 / 职务 / 所属链路，不含手机号、邮箱。
     * 组织树为空时返回空数组，前端据此强制走「新建员工档案」。
     */
    @GetMapping("/register/employees")
    public Result<List<EmployeeVO>> registerEmployees(@RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) Integer limit) {
        return Result.ok(employeeService.registerCandidates(keyword, limit));
    }

    /**
     * 注册页「部门」下拉的选项：组织架构里已存在的部门。
     *
     * <p>与 {@code /register/employees} 同理放在 {@code /auth/**} 下（注册页还没账号）。
     * 只回传部门名称与人数，不含任何员工个人信息。
     */
    @GetMapping("/register/depts")
    public Result<List<Map<String, Object>>> registerDepts() {
        return Result.ok(employeeService.deptList());
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request,
                               @RequestHeader(value = LoginInterceptor.TOKEN_HEADER, required = false) String token) {
        if (token == null) {
            token = request.getHeader(LoginInterceptor.TOKEN_HEADER);
        }
        authService.logout(token);
        return Result.ok();
    }

    @GetMapping("/info")
    public Result<CurrentUser> info() {
        CurrentUser u = AuthContext.get();
        if (u == null) {
            return Result.fail(401, "未登录");
        }
        return Result.ok(u);
    }

    @GetMapping("/dict")
    public Result<Object> dict() {
        return Result.ok(DictHolder.all());
    }
}
