package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.caseflow.dto.SuspectSaveRequest;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseSuspect;
import com.caseflow.entity.SysUser;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CaseSuspectMapper;
import com.caseflow.mapper.SysUserMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.DictHolder;
import com.caseflow.support.LogService;
import com.caseflow.vo.SuspectVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 嫌疑人身份信息：随案件一起存档，增删改全部记快照日志（可撤回）。
 *
 * <p>沿用案件主链路的约定：**每个写操作都带 before / after 快照**，
 * 所以「录错一个嫌疑人」也能用撤回上一步整体还原。
 */
@Service
public class SuspectService {

    @Resource
    private CaseSuspectMapper suspectMapper;
    @Resource
    private CaseAssigneeMapper assigneeMapper;
    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private SysUserMapper userMapper;
    @Resource
    private CaseSnapshotService snapshotService;
    @Resource
    private LogService logService;

    /** 某案件的嫌疑人列表 */
    public List<SuspectVO> listOf(Long caseId, Map<Long, String> userNameMap) {
        List<CaseSuspect> list = suspectMapper.selectList(new LambdaQueryWrapper<CaseSuspect>()
                .eq(CaseSuspect::getCaseId, caseId).orderByAsc(CaseSuspect::getId));
        return list.stream().map(s -> toVO(s, userNameMap)).collect(Collectors.toList());
    }

    public List<SuspectVO> listOf(Long caseId) {
        return listOf(caseId, new HashMap<>());
    }

    // ------------------------------------------------------------------
    // 写操作
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public List<SuspectVO> add(SuspectSaveRequest req) {
        CaseInfo c = requireCase(req.getCaseId());
        if (!StringUtils.hasText(req.getName())) {
            throw new BizException("请填写嫌疑人姓名");
        }
        checkOperate(c);
        String before = snapshotService.capture(c.getId());
        CaseSuspect s = new CaseSuspect();
        s.setCaseId(c.getId());
        s.setName(req.getName().trim());
        s.setGender(normGender(req.getGender()));
        s.setIdCard(req.getIdCard());
        s.setPhone(req.getPhone());
        s.setAddress(req.getAddress());
        // 强制措施（2026-10-18）：选了措施就必须有起止时间，校验不过直接拒绝落库
        validateMeasure(req);
        s.setMeasure(normMeasure(req.getMeasure()));
        s.setMeasureStartAt(req.getMeasureStartAt());
        s.setMeasureEndAt(req.getMeasureEndAt());
        s.setRemark(req.getRemark());
        s.setCreatedBy(AuthContext.userId());
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(s.getCreatedAt());
        suspectMapper.insert(s);

        c.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(c);

        logService.log("CASE", "SUSPECT_ADD", "CASE", c.getId(),
                "新增嫌疑人：" + s.getName(), before, snapshotService.capture(c.getId()));
        return listOf(c.getId());
    }

    /**
     * 建案时随案件一并录入嫌疑人（2026-10-13）。
     *
     * <p><b>与 {@link #add} 的区别：不写日志、不单独打快照</b>。
     * 建案本身已有一条 {@code CASE.CREATE} 日志且 after 快照里带全部嫌疑人；
     * 若这里再调 {@code add()}，会多出一条 {@code SUSPECT_ADD} 日志占据
     * 「案件最新一步」，导致用户点撤回时撤掉的是嫌疑人而不是建案本身——
     * 与用户的直觉相反。
     *
     * <p>没填完的半行（无姓名）直接跳过，不报错打断建案。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<SuspectVO> addAllQuietly(Long caseId, List<SuspectSaveRequest> reqs) {
        if (reqs == null || reqs.isEmpty()) {
            return listOf(caseId);
        }
        LocalDateTime now = LocalDateTime.now();
        for (SuspectSaveRequest r : reqs) {
            if (r == null || !StringUtils.hasText(r.getName())) {
                continue;
            }
            CaseSuspect s = new CaseSuspect();
            s.setCaseId(caseId);
            s.setName(r.getName().trim());
            s.setGender(normGender(r.getGender()));
            s.setIdCard(r.getIdCard());
            s.setPhone(r.getPhone());
            s.setAddress(r.getAddress());
            // 建案时一并录入的嫌疑人不强制校验：这里的表单通常还没有措施字段，
            // 措施在案件详情的嫌疑人面板里补充（那里有完整校验）
            s.setMeasure(normMeasure(r.getMeasure()));
            s.setMeasureStartAt(r.getMeasureStartAt());
            s.setMeasureEndAt(r.getMeasureEndAt());
            s.setRemark(r.getRemark());
            s.setCreatedBy(AuthContext.userId());
            s.setCreatedAt(now);
            s.setUpdatedAt(now);
            suspectMapper.insert(s);
        }
        return listOf(caseId);
    }

    @Transactional(rollbackFor = Exception.class)
    public List<SuspectVO> update(SuspectSaveRequest req) {
        if (req.getId() == null) {
            throw new BizException("缺少嫌疑人 ID");
        }
        CaseSuspect old = suspectMapper.selectById(req.getId());
        if (old == null) {
            throw new BizException("嫌疑人记录不存在");
        }
        checkOperate(requireCase(old.getCaseId()));
        String before = snapshotService.capture(old.getCaseId());
        old.setName(StringUtils.hasText(req.getName()) ? req.getName().trim() : old.getName());
        if (req.getGender() != null) {
            old.setGender(normGender(req.getGender()));
        }
        copyIfPresent(old, req);
        // 强制措施三列单独处理：允许改回「未采取」并把时间清干净。
        // updateById 会跳过 null 字段，清空必须再用 UpdateWrapper 显式 set 一次
        // （与案件级措施 CaseService#transfer 里的 clearMeasure 同款做法）。
        boolean clearMeasureTimes = applyMeasure(old, req);
        old.setUpdatedAt(LocalDateTime.now());
        suspectMapper.updateById(old);
        if (clearMeasureTimes) {
            suspectMapper.update(null, new LambdaUpdateWrapper<CaseSuspect>()
                    .eq(CaseSuspect::getId, old.getId())
                    .set(CaseSuspect::getMeasure, "NONE")
                    .set(CaseSuspect::getMeasureStartAt, null)
                    .set(CaseSuspect::getMeasureEndAt, null));
        }

        CaseInfo c = caseMapper.selectById(old.getCaseId());
        if (c != null) {
            c.setUpdatedAt(LocalDateTime.now());
            caseMapper.updateById(c);
        }
        logService.log("CASE", "SUSPECT_UPDATE", "CASE", old.getCaseId(),
                "修改嫌疑人：" + old.getName(), before, snapshotService.capture(old.getCaseId()));
        return listOf(old.getCaseId());
    }

    @Transactional(rollbackFor = Exception.class)
    public List<SuspectVO> remove(Long id) {
        CaseSuspect s = suspectMapper.selectById(id);
        if (s == null) {
            throw new BizException("嫌疑人记录不存在");
        }
        Long caseId = s.getCaseId();
        checkOperate(requireCase(caseId));
        String before = snapshotService.capture(caseId);
        suspectMapper.deleteById(id);

        CaseInfo c = caseMapper.selectById(caseId);
        if (c != null) {
            c.setUpdatedAt(LocalDateTime.now());
            caseMapper.updateById(c);
        }
        logService.log("CASE", "SUSPECT_DELETE", "CASE", caseId,
                "删除嫌疑人：" + s.getName(), before, snapshotService.capture(caseId));
        return listOf(caseId);
    }

    // ------------------------------------------------------------------

    private CaseInfo requireCase(Long caseId) {
        if (caseId == null) {
            throw new BizException("缺少案件 ID");
        }
        CaseInfo c = caseMapper.selectById(caseId);
        if (c == null) {
            throw new BizException("案件不存在");
        }
        return c;
    }

    /**
     * 写操作权限：管理层 或 研判员 或 本案现职承办人。
     * 原来完全无校验，任何登录账号都能改任意案件的嫌疑人——与待办/计划同一口径补齐。
     * 2026-10-29 放行研判员：研判员的职责就是查看全量案件并录入嫌疑人身份信息。
     */
    private void checkOperate(CaseInfo c) {
        if (AuthContext.isFullAccess() || AuthContext.isJudge()) {
            return;
        }
        Long empId = AuthContext.get() == null ? null : AuthContext.get().getEmployeeId();
        if (empId == null) {
            throw new BizException(403, "只有案件承办人或管理层可以维护嫌疑人信息");
        }
        Long cnt = assigneeMapper.selectCount(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getCaseId, c.getId())
                .eq(CaseAssignee::getEmployeeId, empId)
                .eq(CaseAssignee::getStatus, "ACTIVE"));
        if (cnt == null || cnt == 0) {
            throw new BizException(403, "只有案件承办人或管理层可以维护嫌疑人信息");
        }
    }

    /** 性别只认 MALE / FEMALE，其余一律当未填写，不让脏值进库 */
    private String normGender(String gender) {
        if (!StringUtils.hasText(gender)) {
            return null;
        }
        String g = gender.trim().toUpperCase();
        return "MALE".equals(g) || "FEMALE".equals(g) ? g : null;
    }

    // ------------------------------------------------------------------
    // 强制措施（2026-10-18）：单人单措施 + 起止时间
    // ------------------------------------------------------------------

    /**
     * 校验：选了具体措施就必须有起止时间。
     *
     * <p>为什么不许留空：这两个时间就是办案期限本身。只记「拘留」不记
     * 「从什么时候到什么时候」，到期提醒与期限统计都无从算起，等于把最有用的信息丢了。
     */
    private void validateMeasure(SuspectSaveRequest req) {
        if (req == null) {
            return;
        }
        String m = normMeasure(req.getMeasure());
        if (m == null || "NONE".equals(m)) {
            return;
        }
        String label = DictHolder.name("CASE_MEASURE", m);
        if (!StringUtils.hasText(label)) {
            label = m;
        }
        if (req.getMeasureStartAt() == null) {
            throw new BizException("已选择强制措施「" + label + "」，请填写措施开始时间");
        }
        if (req.getMeasureEndAt() == null) {
            throw new BizException("已选择强制措施「" + label + "」，请填写措施结束时间（下一节点）");
        }
        if (req.getMeasureEndAt().isBefore(req.getMeasureStartAt())) {
            throw new BizException("措施结束时间不能早于开始时间");
        }
    }

    /** 措施归一：空串视为未填（返回 null），其余转大写。 */
    private String normMeasure(String raw) {
        return StringUtils.hasText(raw) ? raw.trim().toUpperCase() : null;
    }

    /**
     * 把请求里的措施三列写进实体。
     *
     * @return true 表示这次是「改回未采取」，调用方需再用 UpdateWrapper 清一次时间列
     *         （updateById 跳过 null，不清就会留下与措施矛盾的旧时间）
     */
    private boolean applyMeasure(CaseSuspect s, SuspectSaveRequest req) {
        if (req.getMeasure() == null) {
            // 前端没传 = 这次不改措施；编辑其它字段时不能把已有措施抹掉
            return false;
        }
        validateMeasure(req);
        String m = normMeasure(req.getMeasure());
        if (m == null || "NONE".equals(m)) {
            s.setMeasure("NONE");
            s.setMeasureStartAt(null);
            s.setMeasureEndAt(null);
            return true;
        }
        s.setMeasure(m);
        s.setMeasureStartAt(req.getMeasureStartAt());
        s.setMeasureEndAt(req.getMeasureEndAt());
        return false;
    }

    private void copyIfPresent(CaseSuspect s, SuspectSaveRequest req) {
        if (req.getIdCard() != null) {
            s.setIdCard(req.getIdCard());
        }
        if (req.getPhone() != null) {
            s.setPhone(req.getPhone());
        }
        if (req.getAddress() != null) {
            s.setAddress(req.getAddress());
        }
        if (req.getRemark() != null) {
            s.setRemark(req.getRemark());
        }
    }

    private SuspectVO toVO(CaseSuspect s, Map<Long, String> userNameMap) {
        SuspectVO vo = new SuspectVO();
        vo.setId(s.getId());
        vo.setCaseId(s.getCaseId());
        vo.setName(s.getName());
        vo.setGender(s.getGender());
        vo.setGenderName(DictHolder.name("GENDER", s.getGender()));
        vo.setIdCard(s.getIdCard());
        vo.setPhone(s.getPhone());
        vo.setAddress(s.getAddress());
        vo.setMeasure(s.getMeasure());
        vo.setMeasureName(DictHolder.name("CASE_MEASURE", s.getMeasure()));
        vo.setMeasureStartAt(s.getMeasureStartAt());
        vo.setMeasureEndAt(s.getMeasureEndAt());
        vo.setRemark(s.getRemark());
        if (userNameMap != null && s.getCreatedBy() != null) {
            vo.setCreatedByName(userNameMap.get(s.getCreatedBy()));
        }
        if (vo.getCreatedByName() == null && s.getCreatedBy() != null) {
            SysUser u = userMapper.selectById(s.getCreatedBy());
            vo.setCreatedByName(u == null ? null : u.getDisplayName());
        }
        vo.setCreatedAt(s.getCreatedAt());
        vo.setUpdatedAt(s.getUpdatedAt());
        return vo;
    }
}
