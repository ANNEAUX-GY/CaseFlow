package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.dto.EmployeeBrief;
import com.caseflow.entity.OrgEmployee;
import com.caseflow.entity.SysUser;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.OrgEmployeeMapper;
import com.caseflow.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

/**
 * 「账号必须关联员工」这条规则的<b>唯一执行点</b>。
 *
 * <p>注册、管理员免审建号、注册申请审核通过，三条入口都要过这里，
 * 避免同一个规则散成三份实现、早晚写歪。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>员工档案必须存在，且未离职/停用；</li>
 *   <li>一个员工只能被一个账号绑定（1:1）—— 案件是按 employee_id 过滤的，
 *       两个人共用一个档案会互相看到对方的案件；</li>
 *   <li>没有可选档案时必须现场建档（{@link EmployeeBrief}），不允许留空跳过。</li>
 * </ol>
 */
@Service
public class EmployeeBindingService {

    /** 档案来源：注册时本人自建 */
    public static final String ORIGIN_SELF_REGISTER = "SELF_REGISTER";
    /** 档案来源：管理员在账号弹窗里快速新增 */
    public static final String ORIGIN_MANUAL = "MANUAL";

    @Resource
    private SysUserMapper userMapper;

    @Resource
    private OrgEmployeeMapper employeeMapper;

    @Resource
    private EmployeeService employeeService;

    /**
     * 把请求里的「选已有档案 / 现场新建」解析成一个合法的员工 ID，并校验可绑定。
     *
     * @param employeeId   选中的已有档案 ID，可为空
     * @param newEmployee  现场新建的档案，可为空
     * @param origin       新建时的来源标记
     * @param excludeUserId 唯一性校验时排除的账号（改绑自己时传自己）
     * @return 最终绑定的员工 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long resolve(Long employeeId, EmployeeBrief newEmployee, String origin, Long excludeUserId) {
        Long id = employeeId;
        if (id == null) {
            if (newEmployee == null || isBlank(newEmployee.getName())) {
                throw new BizException("请为账号关联一名员工：从组织架构中选择已有的本人档案，"
                        + "找不到就点「新建员工档案」现场建立");
            }
            id = employeeService.createBrief(newEmployee, origin).getId();
        }
        checkUsable(id, excludeUserId);
        return id;
    }

    /** 校验档案存在、在职、且没有被别的账号占用 */
    public void checkUsable(Long employeeId, Long excludeUserId) {
        if (employeeId == null) {
            throw new BizException("请为账号关联一名员工");
        }
        OrgEmployee e = employeeMapper.selectById(employeeId);
        if (e == null) {
            throw new BizException("关联的员工档案不存在，请重新选择");
        }
        if (e.getStatus() != null && e.getStatus() == 0) {
            throw new BizException("员工「" + e.getName() + "」已标记为离职/停用，不能关联账号");
        }
        List<SysUser> bound = userMapper.selectList(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmployeeId, employeeId));
        for (SysUser other : bound) {
            if (excludeUserId != null && excludeUserId.equals(other.getId())) {
                continue;
            }
            String who = other.getDisplayName() == null || other.getDisplayName().isEmpty()
                    ? other.getUsername() : other.getDisplayName();
            throw new BizException("员工「" + e.getName() + "」已绑定账号「" + who
                    + "」，一名员工只能绑定一个账号。若确需换绑，请先处理原账号");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
