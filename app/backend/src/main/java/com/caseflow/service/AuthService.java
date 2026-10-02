package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.dto.LoginRequest;
import com.caseflow.dto.RegisterRequest;
import com.caseflow.entity.OrgEmployee;
import com.caseflow.entity.SysUser;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.OrgEmployeeMapper;
import com.caseflow.mapper.SysUserMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.security.CurrentUser;
import com.caseflow.security.PasswordService;
import com.caseflow.security.Roles;
import com.caseflow.security.TokenStore;
import com.caseflow.support.LogService;
import com.caseflow.support.Validators;
import com.caseflow.vo.LoginVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录鉴权与自助注册。
 *
 * <p>密码统一 BCrypt 存储；早期明文密码在登录成功时自动升级为哈希（见 {@link PasswordService}）。
 * <p>自行注册的账号默认 audit_status=0（待审核），必须由全权限角色审核通过后才能登录。
 */
@Service
public class AuthService {

    @Resource
    private SysUserMapper userMapper;

    @Resource
    private TokenStore tokenStore;

    @Resource
    private PasswordService passwordService;

    @Resource
    private LogService logService;

    @Resource
    private OrgEmployeeMapper employeeMapper;

    /** 账号必须关联员工的统一校验（注册 / 建号 / 审核共用一个入口） */
    @Resource
    private EmployeeBindingService bindingService;

    @Value("${caseflow.token-expire-hours:12}")
    private long expireHours;

    public LoginVO login(LoginRequest req) {
        SysUser user = findByAccount(Validators.trim(req.getUsername()));
        if (user == null) {
            throw new BizException("账号不存在。可以用用户名或注册手机号登录");
        }
        // 先查审核状态：待审核/被驳回的账号，即便密码对了也不让进
        Integer audit = user.getAuditStatus();
        if (audit != null && audit == 0) {
            throw new BizException("账号正在等待审核，请联系所长或法制员审核通过后再登录");
        }
        if (audit != null && audit == 2) {
            String remark = user.getAuditRemark();
            throw new BizException("注册申请未通过" + (remark == null || remark.isEmpty() ? "" : "：" + remark));
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException("账号已停用，请联系管理员");
        }
        if (!passwordService.matches(req.getPassword(), user.getPassword())) {
            throw new BizException(401, "密码错误");
        }
        // 历史明文密码：这次比对成功了，顺手升级成 BCrypt，下次就走哈希
        if (!passwordService.isHashed(user.getPassword())) {
            SysUser upgrade = new SysUser();
            upgrade.setId(user.getId());
            upgrade.setPassword(passwordService.encode(req.getPassword()));
            upgrade.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(upgrade);
        }

        CurrentUser current = new CurrentUser(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRole(), user.getEmployeeId());
        String token = tokenStore.createSession(current, expireHours);

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setRole(user.getRole());
        vo.setRoleName(Roles.name(user.getRole()));
        vo.setEmployeeId(user.getEmployeeId());
        vo.setFullAccess(Roles.isFullAccess(user.getRole()));
        logService.log("AUTH", "LOGIN", "USER", user.getId(),
                user.getDisplayName() + "（" + Roles.name(user.getRole()) + "）登录系统");
        return vo;
    }

    /**
     * 按账号找用户：先按登录名精确匹配，找不到再按手机号匹配。
     *
     * <p>用 selectList + 取首条而不是 selectOne，是为了避免历史数据出现同名/同手机号时
     * MyBatis-Plus 直接抛 TooManyResultsException（用户名在库里是 UNIQUE，这里只是兜底）。
     */
    private SysUser findByAccount(String account) {
        if (account == null || account.isEmpty()) {
            return null;
        }
        List<SysUser> byName = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, account));
        if (!byName.isEmpty()) {
            return byName.get(0);
        }
        List<SysUser> byPhone = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, account));
        return byPhone.isEmpty() ? null : byPhone.get(0);
    }

    /**
     * 自助注册。注册后为「待审核」状态，不能立即登录。
     *
     * <p>用手机号作为账号标识：登录名可以留空，留空时自动用手机号当登录名。
     * <p>不允许注册成 BOSS（系统管理员），其余角色可以申请，但最终以审核确认的角色为准。
     * <p><b>必须关联一名员工</b>：组织树里已有本人档案就选 {@code employeeId}，
     * 没有就带 {@code newEmployee} 现场建档，两者都缺会被拒绝登录前就拦下。
     * 加事务是为了让「现场建档 + 建号」要么一起成、要么一起滚，不留孤儿档案。
     */
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest req) {
        String username = Validators.trim(req.getUsername());
        String password = req.getPassword();
        String displayName = Validators.trim(req.getDisplayName());
        String applyRole = Validators.trim(req.getApplyRole());
        String phone = Validators.trim(req.getPhone());

        // ---- 手机号：必填 + 格式 + 唯一（手机号就是登录凭据，不能重复） ----
        if (phone == null || phone.isEmpty()) {
            throw new BizException("请填写手机号，手机号可用于登录");
        }
        if (!Validators.isPhone(phone)) {
            throw new BizException("手机号格式不正确，应为 11 位数字");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, phone)) > 0) {
            throw new BizException("手机号「" + Validators.maskPhone(phone) + "」已被注册，请换一个或联系管理员");
        }

        if (password == null || password.length() < 6) {
            throw new BizException("密码至少 6 位");
        }
        if (displayName == null || displayName.isEmpty()) {
            throw new BizException("请填写真实姓名");
        }
        if (applyRole == null || applyRole.isEmpty()) {
            applyRole = Roles.STAFF;
        }
        if (!Roles.isApplicable(applyRole)) {
            throw new BizException("申请角色不合法");
        }

        // ---- 登录名：可留空，留空则用手机号 ----
        if (username == null || username.isEmpty()) {
            username = phone;
        } else {
            if (username.length() < 3) {
                throw new BizException("登录名至少 3 个字符，或留空直接用手机号");
            }
            if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, username)) > 0) {
                throw new BizException("登录名「" + username + "」已被占用，请换一个或留空用手机号");
            }
        }

        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(passwordService.encode(password));
        u.setDisplayName(displayName);
        u.setRole(applyRole);
        u.setApplyRole(applyRole);
        u.setPhone(phone);
        u.setDept(Validators.trim(req.getDept()));
        // 新账号必须关联员工档案：选已有的，或组织树里还没这个人就现场建一个（同一事务，一起回滚）
        Long employeeId = bindingService.resolve(req.getEmployeeId(), req.getNewEmployee(),
                EmployeeBindingService.ORIGIN_SELF_REGISTER, null);
        u.setEmployeeId(employeeId);
        u.setAuditStatus(0);
        u.setStatus(1);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(u);

        OrgEmployee emp = employeeMapper.selectById(employeeId);
        logService.log("AUTH", "REGISTER", "USER", u.getId(),
                "提交注册申请：" + displayName + "（手机 " + Validators.maskPhone(phone)
                        + "，登录名 " + username + "，申请角色 " + Roles.name(applyRole)
                        + "，关联员工 " + (emp == null ? employeeId : emp.getName()) + "）");
    }

    public void logout(String token) {
        CurrentUser u = AuthContext.get();
        tokenStore.remove(token);
        if (u != null) {
            logService.log("AUTH", "LOGOUT", "USER", u.getUserId(), u.getDisplayName() + " 退出登录");
        }
    }

    /** 首次启动兜底创建管理员账号（密码同样走 BCrypt） */
    public void ensureAdmin() {
        long count = userMapper.selectCount(null);
        if (count == 0) {
            SysUser admin = new SysUser();
            admin.setUsername("boss");
            admin.setPassword(passwordService.encode("admin123"));
            admin.setDisplayName("系统管理员");
            admin.setRole(Roles.BOSS);
            admin.setAuditStatus(1);
            admin.setStatus(1);
            admin.setCreatedAt(LocalDateTime.now());
            admin.setUpdatedAt(LocalDateTime.now());
            userMapper.insert(admin);
        }
    }
}