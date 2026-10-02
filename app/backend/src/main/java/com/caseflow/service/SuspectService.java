package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.dto.SuspectSaveRequest;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseSuspect;
import com.caseflow.entity.SysUser;
import com.caseflow.exception.BizException;
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
        String before = snapshotService.capture(c.getId());
        CaseSuspect s = new CaseSuspect();
        s.setCaseId(c.getId());
        s.setName(req.getName().trim());
        s.setGender(normGender(req.getGender()));
        s.setIdCard(req.getIdCard());
        s.setPhone(req.getPhone());
        s.setAddress(req.getAddress());
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

    @Transactional(rollbackFor = Exception.class)
    public List<SuspectVO> update(SuspectSaveRequest req) {
        if (req.getId() == null) {
            throw new BizException("缺少嫌疑人 ID");
        }
        CaseSuspect old = suspectMapper.selectById(req.getId());
        if (old == null) {
            throw new BizException("嫌疑人记录不存在");
        }
        String before = snapshotService.capture(old.getCaseId());
        old.setName(StringUtils.hasText(req.getName()) ? req.getName().trim() : old.getName());
        if (req.getGender() != null) {
            old.setGender(normGender(req.getGender()));
        }
        copyIfPresent(old, req);
        old.setUpdatedAt(LocalDateTime.now());
        suspectMapper.updateById(old);

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

    /** 性别只认 MALE / FEMALE，其余一律当未填写，不让脏值进库 */
    private String normGender(String gender) {
        if (!StringUtils.hasText(gender)) {
            return null;
        }
        String g = gender.trim().toUpperCase();
        return "MALE".equals(g) || "FEMALE".equals(g) ? g : null;
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
