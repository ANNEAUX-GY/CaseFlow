package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.dto.UserSaveRequest;
import com.caseflow.entity.OrgEmployee;
import com.caseflow.entity.SysUser;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.OrgEmployeeMapper;
import com.caseflow.mapper.SysUserMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.security.CurrentUser;
import com.caseflow.security.PasswordService;
import com.caseflow.security.Roles;
import com.caseflow.support.LogService;
import com.caseflow.support.Validators;
import com.caseflow.vo.UserVO;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 账号管理：注册审核、角色分配、启用停用、重置密码。
 *
 * <p>本服务只应通过 {@code UserController}（挂了 {@code @FullAccessOnly}）调用。
 *
 * <p>两条防呆规则，避免管理员把自己锁在门外：
 * <ol>
 *   <li>不能停用 / 删除 / 降级「自己」这个账号；</li>
 *   <li>不能让系统里最后一个全权限账号失去全权限。</li>
 * </ol>
 */
@Service
public class UserService {

    @Resource
    private SysUserMapper userMapper;

    @Resource
    private OrgEmployeeMapper employeeMapper;

    @Resource
    private PasswordService passwordService;

    @Resource
    private LogService logService;

    @Resource
    private com.caseflow.security.TokenStore tokenStore;

    /** 账号必须关联员工的统一校验（注册 / 建号 / 审核共用一个入口） */
    @Resource
    private EmployeeBindingService bindingService;

    @Resource
    private EmployeeService employeeService;

    /** 账号列表；auditStatus 传 0 可只看待审核 */
    public List<UserVO> list(String keyword, Integer auditStatus) {
        LambdaQueryWrapper<SysUser> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String k = keyword.trim();
            w.and(q -> q.like(SysUser::getUsername, k)
                    .or().like(SysUser::getDisplayName, k)
                    .or().like(SysUser::getPhone, k));
        }
        if (auditStatus != null) {
            w.eq(SysUser::getAuditStatus, auditStatus);
        }
        w.orderByAsc(SysUser::getAuditStatus).orderByDesc(SysUser::getId);
        List<SysUser> users = userMapper.selectList(w);
        return toVOs(users);
    }

    /** 待审核数量（导航红点用） */
    public long pendingCount() {
        Long n = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getAuditStatus, 0));
        return n == null ? 0 : n;
    }

    /**
     * 关联员工下拉的可选项：排除已被其他账号占用的员工。
     *
     * <p>一个员工只能绑一个账号，所以已经绑过的就不该再出现在候选里 ——
     * 让管理员在界面上就选不到，比提交后弹一句「该员工已绑定账号」友好得多。
     * 编辑场景传 {@code excludeUserId} 把当前账号自己排除掉，否则会看不到自己已绑的那个人。
     */
    public List<com.caseflow.vo.EmployeeVO> bindableEmployees(String keyword, Integer limit, Long excludeUserId) {
        int cap = (limit == null || limit <= 0) ? 30 : Math.min(limit, 100);
        List<com.caseflow.vo.EmployeeVO> all = employeeService.search(keyword, 200);
        Set<Long> taken = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .isNotNull(SysUser::getEmployeeId)).stream()
                .filter(x -> excludeUserId == null || !excludeUserId.equals(x.getId()))
                .map(SysUser::getEmployeeId)
                .collect(Collectors.toSet());
        return all.stream()
                .filter(v -> !taken.contains(v.getId()))
                .limit(cap)
                .collect(Collectors.toList());
    }

    /** 审核通过，并确认最终角色（可与申请角色不同） */
    @Transactional(rollbackFor = Exception.class)
    public UserVO approve(Long id, UserSaveRequest req) {
        SysUser u = mustGet(id);
        String role = req.getRole() == null || req.getRole().isEmpty() ? u.getApplyRole() : req.getRole();
        if (role == null || role.isEmpty()) {
            role = Roles.STAFF;
        }
        if (!Roles.isValid(role)) {
            throw new BizException("角色不合法：" + role);
        }
        String before = snapshot(u);

        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setRole(role);
        upd.setAuditStatus(1);
        upd.setAuditRemark(req.getRemark());
        upd.setAuditedBy(AuthContext.userId());
        upd.setAuditedAt(LocalDateTime.now());
        upd.setStatus(1);
        // 审核时确定最终绑定的员工：管理员可在弹窗里改绑，没改就沿用注册时申请人自己认领的那份档案
        Long employeeId = req.getEmployeeId() != null ? req.getEmployeeId() : u.getEmployeeId();
        if (employeeId == null) {
            if (req.getNewEmployee() == null) {
                throw new BizException("审核通过前必须为账号关联一名员工，"
                        + "请在「关联员工」中选择，或点「新建员工档案」现场建立");
            }
            employeeId = bindingService.resolve(null, req.getNewEmployee(),
                    EmployeeBindingService.ORIGIN_MANUAL, id);
        } else {
            bindingService.checkUsable(employeeId, id);
        }
        upd.setEmployeeId(employeeId);
        upd.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(upd);

        logService.log("AUTH", "APPROVE_USER", "USER", id,
                "审核通过账号：" + u.getDisplayName() + "，角色设为 " + Roles.name(role),
                before, snapshot(mustGet(id)));
        return toVOs(Collections.singletonList(mustGet(id))).get(0);
    }

    /** 驳回注册申请 */
    @Transactional(rollbackFor = Exception.class)
    public UserVO reject(Long id, UserSaveRequest req) {
        SysUser u = mustGet(id);
        String before = snapshot(u);
        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setAuditStatus(2);
        upd.setAuditRemark(req.getRemark() == null || req.getRemark().isEmpty() ? "不符合使用要求" : req.getRemark());
        upd.setAuditedBy(AuthContext.userId());
        upd.setAuditedAt(LocalDateTime.now());
        upd.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(upd);
        logService.log("AUTH", "REJECT_USER", "USER", id,
                "驳回注册申请：" + u.getDisplayName(), before, snapshot(mustGet(id)));
        return toVOs(Collections.singletonList(mustGet(id))).get(0);
    }

    /** 管理员直接新建账号（免审核） */
    @Transactional(rollbackFor = Exception.class)
    public UserVO create(UserSaveRequest req) {
        String username = req.getUsername() == null ? null : req.getUsername().trim();
        if (username == null || username.length() < 3) {
            throw new BizException("登录名至少 3 个字符");
        }
        if (req.getPassword() == null || req.getPassword().length() < 6) {
            throw new BizException("密码至少 6 位");
        }
        String role = req.getRole() == null || req.getRole().isEmpty() ? Roles.STAFF : req.getRole();
        if (!Roles.isValid(role)) {
            throw new BizException("角色不合法：" + role);
        }
        Long exist = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (exist != null && exist > 0) {
            throw new BizException("登录名「" + username + "」已存在");
        }
        String phone = checkPhone(req.getPhone(), null);
        // 新账号必须关联员工：选已有的，或现场建档（与建号同一事务，失败一起回滚）
        Long employeeId = bindingService.resolve(req.getEmployeeId(), req.getNewEmployee(),
                EmployeeBindingService.ORIGIN_MANUAL, null);
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(passwordService.encode(req.getPassword()));
        u.setDisplayName(req.getDisplayName() == null || req.getDisplayName().isEmpty()
                ? username : req.getDisplayName().trim());
        u.setRole(role);
        u.setEmployeeId(employeeId);
        u.setPhone(phone);
        u.setDept(req.getDept());
              // 办案组别：普通民警必选（前端已拦），后端兜底归一为「不限」
        u.setPoliceGroup(com.caseflow.flow.PoliceGroup.normalize(req.getPoliceGroup()));
        u.setAuditStatus(1);
        u.setStatus(1);
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(u);
        OrgEmployee emp = employeeMapper.selectById(employeeId);
        logService.log("AUTH", "CREATE_USER", "USER", u.getId(),
                "新建账号：" + u.getDisplayName() + "，角色 " + Roles.name(role)
                        + "，关联员工 " + (emp == null ? employeeId : emp.getName()));
        return toVOs(Collections.singletonList(u)).get(0);
    }

    /** 修改基本信息 / 角色 */
    @Transactional(rollbackFor = Exception.class)
    public UserVO update(Long id, UserSaveRequest req) {
        SysUser u = mustGet(id);
        String before = snapshot(u);
        String newRole = req.getRole() == null || req.getRole().isEmpty() ? u.getRole() : req.getRole();
        if (!Roles.isValid(newRole)) {
            throw new BizException("角色不合法：" + newRole);
        }
        // 不能把自己降级出全权限，否则可能没人能再进账号管理
        if (id.equals(AuthContext.userId())
                && Roles.isFullAccess(u.getRole()) && !Roles.isFullAccess(newRole)) {
            throw new BizException("不能修改自己的角色，避免把自己锁在门外");
        }
        if (Roles.isFullAccess(u.getRole()) && !Roles.isFullAccess(newRole)) {
            ensureNotLastFullAccess("降级");
        }

        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setDisplayName(req.getDisplayName());
        upd.setRole(newRole);
        // 关联员工：传了就改绑（校验唯一性），没传就维持原样。
        // 刻意做成「留空=不改」而不是「留空=解绑」——账号必须绑员工，
        // 不该因为前端表单没带这个字段就把绑定清掉。
        if (req.getEmployeeId() != null || req.getNewEmployee() != null) {
            upd.setEmployeeId(bindingService.resolve(req.getEmployeeId(), req.getNewEmployee(),
                    EmployeeBindingService.ORIGIN_MANUAL, id));
        }
        upd.setPhone(checkPhone(req.getPhone(), id));
        upd.setDept(req.getDept());
        // 组别：编辑时传了才改（帮申请人纠正填错的）。
        // 不传就跳过——normalize() 对空值会返回「不限」，无条件写入会把
        // 没带这个字段的编辑请求里的组别静默重置成 NONE。
        if (req.getPoliceGroup() != null && !req.getPoliceGroup().trim().isEmpty()) {
            upd.setPoliceGroup(com.caseflow.flow.PoliceGroup.normalize(req.getPoliceGroup()));
        }
        upd.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(upd);
        logService.log("AUTH", "UPDATE_USER", "USER", id,
                "修改账号：" + u.getDisplayName(), before, snapshot(mustGet(id)));
        return toVOs(Collections.singletonList(mustGet(id))).get(0);
    }

    /** 重置密码 */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, String rawPassword) {
        SysUser u = mustGet(id);
        if (rawPassword == null || rawPassword.length() < 6) {
            throw new BizException("密码至少 6 位");
        }
        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setPassword(passwordService.encode(rawPassword));
        upd.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(upd);
        // 重置密码后把旧的登录态全部踢掉 —— 否则别人手上的会话还能继续用新密码前的旧令牌
        int kicked = tokenStore.removeAll(id);
        // 注意：日志里绝不能记明文密码
        logService.log("AUTH", "RESET_PWD", "USER", id,
                "重置密码：" + u.getDisplayName() + (kicked > 0 ? "（已踢下线 " + kicked + " 个会话）" : ""));
    }

    /** 启用 / 停用 */
    @Transactional(rollbackFor = Exception.class)
    public UserVO changeStatus(Long id, Integer status) {
        SysUser u = mustGet(id);
        int s = (status != null && status == 0) ? 0 : 1;
        if (s == 0) {
            if (id.equals(AuthContext.userId())) {
                throw new BizException("不能停用当前登录的账号");
            }
            if (Roles.isFullAccess(u.getRole())) {
                ensureNotLastFullAccess("停用");
            }
        }
        String before = snapshot(u);
        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setStatus(s);
        upd.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(upd);
        // 停用要立刻把该账号踢下线：拦截器只校验令牌、不会每次回查 status，
        // 光改状态位的话，对方手上的旧令牌能一直用到过期为止。
        int kicked = s == 0 ? tokenStore.removeAll(id) : 0;
        logService.log("AUTH", "STATUS_USER", "USER", id,
                (s == 1 ? "启用账号：" : "停用账号：" + (kicked > 0 ? "（已踢下线 " + kicked + " 个会话）" : ""))
                        + u.getDisplayName(),
                before, snapshot(mustGet(id)));
        return toVOs(Collections.singletonList(mustGet(id))).get(0);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysUser u = mustGet(id);
        if (id.equals(AuthContext.userId())) {
            throw new BizException("不能删除当前登录的账号");
        }
        if (Roles.isFullAccess(u.getRole())) {
            ensureNotLastFullAccess("删除");
        }
        userMapper.deleteById(id);
        tokenStore.removeAll(id);   // 账号没了，手上的令牌必须立刻失效
        logService.log("AUTH", "DELETE_USER", "USER", id,
                "删除账号：" + u.getDisplayName() + "（" + u.getUsername() + "）");
    }

    /**
     * 校验手机号：可以留空（老账号没有手机号），填写则必须是合法 11 位、且不能被其他账号占用。
     *
     * <p>手机号同时也是登录凭据之一，所以这里和注册走同一套规则。
     */
    private String checkPhone(String raw, Long excludeId) {
        String phone = Validators.trim(raw);
        if (phone == null || phone.isEmpty()) {
            return null;
        }
        if (!Validators.isPhone(phone)) {
            throw new BizException("手机号格式不正确，应为 11 位数字");
        }
        LambdaQueryWrapper<SysUser> w = new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, phone);
        if (excludeId != null) {
            w.ne(SysUser::getId, excludeId);
        }
        if (userMapper.selectCount(w) > 0) {
            throw new BizException("手机号「" + Validators.maskPhone(phone) + "」已被其他账号使用");
        }
        return phone;
    }

    /** 系统里至少保留一个可用的全权限账号 */
    private void ensureNotLastFullAccess(String action) {
        List<SysUser> all = userMapper.selectList(null);
        long usable = all.stream()
                .filter(x -> Roles.isFullAccess(x.getRole()))
                .filter(x -> x.getStatus() != null && x.getStatus() == 1)
                .filter(x -> x.getAuditStatus() == null || x.getAuditStatus() == 1)
                .count();
        if (usable <= 1) {
            throw new BizException("系统必须保留至少一个可用的全权限账号（所长/副所长/法制员），无法" + action);
        }
    }

    private SysUser mustGet(Long id) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw new BizException("账号不存在");
        }
        return u;
    }

    /** 变更前快照，配合操作日志展示「改了什么」 */
    private String snapshot(SysUser u) {
        return "{\"displayName\":\"" + nz(u.getDisplayName())
                + "\",\"role\":\"" + nz(u.getRole())
                + "\",\"auditStatus\":" + (u.getAuditStatus() == null ? 1 : u.getAuditStatus())
                + ",\"status\":" + (u.getStatus() == null ? 1 : u.getStatus())
                + ",\"phone\":\"" + nz(u.getPhone())
                + "\",\"dept\":\"" + nz(u.getDept()) + "\"}";
    }

    private String nz(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }

    private List<UserVO> toVOs(List<SysUser> users) {
        if (users == null || users.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> empIds = users.stream().map(SysUser::getEmployeeId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, OrgEmployee> emps = empIds.isEmpty() ? Collections.emptyMap()
                : employeeMapper.selectBatchIds(empIds).stream()
                .collect(Collectors.toMap(OrgEmployee::getId, e -> e, (a, b) -> a));
        return users.stream().map(u -> {
            UserVO vo = new UserVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            vo.setDisplayName(u.getDisplayName());
            vo.setRole(u.getRole());
            vo.setRoleName(Roles.name(u.getRole()));
            vo.setEmployeeId(u.getEmployeeId());
            OrgEmployee emp = u.getEmployeeId() == null ? null : emps.get(u.getEmployeeId());
            vo.setEmployeeName(emp == null ? null : emp.getName());
            vo.setEmployeeDept(emp == null ? null : emp.getDept());
            vo.setEmployeeOrigin(emp == null ? null : emp.getOrigin());
            vo.setPhone(u.getPhone());
            vo.setDept(u.getDept());
    vo.setApplyRole(u.getApplyRole());
     String ug = com.caseflow.flow.PoliceGroup.normalize(u.getPoliceGroup());
            vo.setPoliceGroup(ug);
            vo.setPoliceGroupName(com.caseflow.flow.PoliceGroup.label(ug));
            vo.setApplyRoleName(u.getApplyRole() == null ? null : Roles.name(u.getApplyRole()));
            vo.setAuditStatus(u.getAuditStatus() == null ? 1 : u.getAuditStatus());
            vo.setAuditRemark(u.getAuditRemark());
            vo.setStatus(u.getStatus() == null ? 1 : u.getStatus());
            vo.setCreatedAt(u.getCreatedAt());
            vo.setAuditedAt(u.getAuditedAt());
            vo.setFullAccess(Roles.isFullAccess(u.getRole()));
            return vo;
        }).collect(Collectors.toList());
    }

    // ---------------- 批量建账号（2026-11-12：档案账号一对一） ----------------

    /**
     * 职务 → 角色映射：与账号「角色」下拉的 8 个词一一对应。
     * 没匹配上（含空职务）一律归为普通民警，避免建出非法角色。
     */
    private static String roleOfTitle(String title) {
        String t = title == null ? "" : title.trim();
        if (t.contains("所长") && !t.contains("副")) return Roles.CHIEF;
        if (t.contains("副")) return Roles.DEPUTY_CHIEF;
        if (t.contains("队长") || t.contains("组长")) return Roles.CAPTAIN;
        if (t.contains("法制")) return Roles.LAW_OFFICER;
        if (t.contains("案管")) return Roles.CASE_MANAGER;
        if (t.contains("研判") || t.contains("辅判") || t.contains("侦判")) return Roles.JUDGE;
        if (t.contains("系统") || t.contains("管理员")) return Roles.BOSS;
        return Roles.STAFF;
    }

    /**
     * 中文姓名 → 全小写拼音（多音字取第一个读音）。
     * 非中文（字母/数字）原样保留小写；无拼音的字符跳过。
     */
    private static String toPinyin(String name) {
        if (name == null || name.isEmpty()) return "";
        HanyuPinyinOutputFormat fmt = new HanyuPinyinOutputFormat();
        fmt.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        fmt.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        fmt.setVCharType(HanyuPinyinVCharType.WITH_V);
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toCharArray()) {
            if (Character.toString(ch).matches("[\\u4e00-\\u9fa5]")) {
                try {
                    String[] arr = PinyinHelper.toHanyuPinyinStringArray(ch, fmt);
                    sb.append(arr == null || arr.length == 0 ? "" : arr[0]);
                } catch (Exception e) {
                    // 单个字转失败不影响整体
                }
            } else if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    /**
     * 为「没有账号」的员工档案批量建立账号，实现档案-账号严格一对一。
     *
     * <p>规则：用户名 = 姓名全小写拼音（重名/重号自动加数字后缀）、密码统一 123456、
     * 角色由职务映射、displayName = 姓名、auditStatus = 1（已通过）。
     * 返回 {created: n, usernames: [...]}，幂等：已有账号的档案自动跳过。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batchCreateAccounts() {
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "只有管理层可以批量建立账号");
        }
        List<OrgEmployee> employees = employeeMapper.selectList(null);
        // 已绑定的员工 ID 集合（一名员工只该有一个账号，但历史数据可能重复绑定，用集合去重）
        Set<Long> boundIds = new HashSet<>();
        for (SysUser u : userMapper.selectList(null)) {
            if (u.getEmployeeId() != null) boundIds.add(u.getEmployeeId());
        }
        // 已有用户名集合，用于重名加后缀
        Set<String> usedNames = new HashSet<>();
        for (SysUser u : userMapper.selectList(null)) {
            if (u.getUsername() != null) usedNames.add(u.getUsername().toLowerCase());
        }

        int created = 0;
        List<String> usernames = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (OrgEmployee e : employees) {
            if (boundIds.contains(e.getId())) continue; // 已有账号，跳过
            if (e.getStatus() != null && e.getStatus() == 0) continue; // 离职/停用不建号
            String base = toPinyin(e.getName());
            if (base.isEmpty()) base = "user";
            String username = uniqueUsername(base, usedNames);
            usedNames.add(username);

            SysUser u = new SysUser();
            u.setUsername(username);
            u.setPassword(passwordService.encode("123456"));
            u.setDisplayName(e.getName());
            u.setRole(roleOfTitle(e.getTitle()));
            u.setEmployeeId(e.getId());
            u.setDept(e.getDept());
            u.setAuditStatus(1);
            u.setStatus(1);
            u.setCreatedAt(now);
            u.setUpdatedAt(now);
            userMapper.insert(u);
            created++;
            usernames.add(username);
        }
        if (created > 0) {
            logService.log("AUTH", "BATCH_CREATE_ACCOUNTS", "USER", null,
                    "批量建立账号 " + created + " 个（档案-账号一对一补齐）");
        }
        Map<String, Object> out = new HashMap<>();
        out.put("created", created);
        out.put("usernames", usernames);
        return out;
    }

    private static String uniqueUsername(String base, Set<String> used) {
        if (!used.contains(base)) return base;
        int i = 1;
        while (used.contains(base + i)) i++;
        return base + i;
    }
}
