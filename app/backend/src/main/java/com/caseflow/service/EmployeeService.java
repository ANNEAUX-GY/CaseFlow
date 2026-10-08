package com.caseflow.service;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.dto.EmployeeBrief;
import com.caseflow.dto.EmployeeSaveRequest;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.OrgEmployee;
import com.caseflow.excel.EmployeeExcelListener;
import com.caseflow.excel.EmployeeExcelRow;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.OrgEmployeeMapper;
import com.caseflow.support.LogService;
import com.caseflow.support.Validators;
import com.caseflow.vo.EmployeeVO;
import com.caseflow.vo.ImportResultVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 员工图谱：Excel 导入 -> 组织树 -> 检索 -> 指派时选择。
 */
@Service
public class EmployeeService {

    @Resource
    private OrgEmployeeMapper employeeMapper;

    @Resource
    private CaseAssigneeMapper assigneeMapper;

    @Resource
    private LogService logService;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    private List<OrgEmployee> loadAll(Integer status) {
        LambdaQueryWrapper<OrgEmployee> q = new LambdaQueryWrapper<>();
        if (status != null) {
            q.eq(OrgEmployee::getStatus, status);
        }
        q.orderByAsc(OrgEmployee::getLevelNo, OrgEmployee::getSortNo, OrgEmployee::getId);
        return employeeMapper.selectList(q);
    }

    /** 全量员工映射：id -> entity */
    private Map<Long, OrgEmployee> idMap(List<OrgEmployee> list) {
        Map<Long, OrgEmployee> map = new LinkedHashMap<>();
        for (OrgEmployee e : list) {
            map.put(e.getId(), e);
        }
        return map;
    }

    /** id -> entity（供其他服务批量翻译姓名/部门用，避免 N 次单查） */
    public Map<Long, OrgEmployee> employeeMap() {
        return idMap(loadAll(null));
    }

    /** id -> "王总 / 李副总 / 张组长" 链路文本（走的是解析后的层级，与组织树一致） */
    public Map<Long, String> pathNameMap() {
        List<OrgEmployee> all = loadAll(null);
        Map<Long, String> cache = new HashMap<>();
        Map<Long, String> result = new HashMap<>();
        for (OrgEmployee e : all) {
            result.put(e.getId(), buildPathName(e.getId(), idMap(all), resolveParents(all, rankMap(all)), cache));
        }
        return result;
    }

    /**
     * 沿解析后的上级链拼出 "王总 / 李副总 / 张组长"。
     *
     * <p>用解析后的 parentOf 而不是库里的 parent_id：两者可能不一致
     * （上级选错层时树上会兜底），链路文本必须和画出来的树一样，
     * 否则会出现「树上挂在张组长下、链路却显示到王总为止」这种对不上的情况。
     */
    private String buildPathName(Long id, Map<Long, OrgEmployee> map, Map<Long, Long> parentOf,
                                Map<Long, String> cache) {
        if (cache.containsKey(id)) {
            return cache.get(id);
        }
        List<String> chain = new ArrayList<>();
        Long cursor = id;
        Set<Long> guard = new HashSet<>();
        while (cursor != null && cursor > 0 && map.containsKey(cursor) && guard.add(cursor)) {
            OrgEmployee cur = map.get(cursor);
            chain.add(0, cur.getName());
            cursor = parentOf.get(cursor);
        }
        String path = String.join(" / ", chain);
        cache.put(id, path);
        return path;
    }

    /**
     * 组织树；keyword 非空时只保留命中节点及其祖先链。
     *
     * <p><b>2026-10-08 起层级由职务决定</b>（见 {@link com.caseflow.flow.OrgRank}）：
     * 总 → 副总 → 组长 → 员工 四层，{@code parent_id} 只决定同层里挂到谁名下。
     * 上级选错层（选成平级或跨级）不会报错，而是自动落到同层的合理上级，
     * 保证组织树永远是这四层——否则一人填错，整棵树就歪了。
     */
    public List<EmployeeVO> tree(String keyword, Integer status) {
        List<OrgEmployee> all = loadAll(status);
        Map<Long, OrgEmployee> map = idMap(all);
        Map<Long, Integer> rankMap = rankMap(all);
        // 先算真实上级，链路文本与挂树都用它，两处必须同源
        Map<Long, Long> effectiveParent = resolveParents(all, rankMap);
        Map<Long, String> cache = new HashMap<>();
        Map<Long, EmployeeVO> voMap = new LinkedHashMap<>();

        Map<Long, Integer> activeCount = activeCaseCount();
        // 部门人数：用来判断「独立部门」（整个部门只有他一个人）
        Map<String, Integer> deptSize = deptSizeMap(all);

        for (OrgEmployee e : all) {
            EmployeeVO vo = toVO(e, activeCount);
            vo.setRank(rankMap.get(e.getId()));
            vo.setRankLabel(com.caseflow.flow.OrgRank.label(vo.getRank()));
            vo.setPathName(buildPathName(e.getId(), map, effectiveParent, cache));
            // 回传解析后的上级，前端「上级」下拉回显的是树上真实挂的那个，
            // 否则管理员点开一个孤儿节点会看到「不选则为顶层」，与画面不符
            Long ep = effectiveParent.get(e.getId());
            vo.setParentId(ep == null ? 0L : ep);
            voMap.put(e.getId(), vo);
        }
        markDeptAnomaly(voMap, deptSize);

        List<EmployeeVO> roots = new ArrayList<>();
        for (OrgEmployee e : all) {
            EmployeeVO vo = voMap.get(e.getId());
            Long pid = vo.getParentId();
            EmployeeVO parent = pid == null ? null : voMap.get(pid);
            if (parent != null && !pid.equals(e.getId())) {
                parent.getChildren().add(vo);
            } else {
                roots.add(vo);
            }
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim().toLowerCase();
            for (EmployeeVO root : roots) {
                markMatched(root, kw);
            }
            roots = roots.stream().filter(this::keepBranch).collect(Collectors.toList());
        }
        return roots;
    }

    /**
     * 按「总-副总-组长-员工」解析每个人真正的上级。
     *
     * <p>规则：先尊重库里已填的上级，但**必须恰好高一级**（{@link com.caseflow.flow.OrgRank#isParentOf}）；
     * 不合法（平级、跨级、上级不存在）时，退回到「本部门同层的第一个人」，
     * 本部门没人就退到全局同层的第一个人。返回 0 表示没有上级（就是根）。
     *
     * <p>为什么要「兜底」而不是直接报错拒绝保存：职务是必填的层级依据，
     * 而上级可能因为调整还没来得及选。让一个人暂时挂错层级，
     * 比让他**保存不了**、进而建不进档案要轻得多。
     */
    private Map<Long, Long> resolveParents(List<OrgEmployee> all, Map<Long, Integer> rankMap) {
        Map<Long, OrgEmployee> map = idMap(all);
        // 每一层按 id 升序排一遍，保证「同层第一个人」这个兜底是稳定的，不随查询顺序漂移
        Map<Integer, List<OrgEmployee>> byRank = new java.util.TreeMap<>();
        for (OrgEmployee e : all) {
            byRank.computeIfAbsent(rankMap.get(e.getId()), k -> new ArrayList<>()).add(e);
        }
        for (List<OrgEmployee> layer : byRank.values()) {
            layer.sort(Comparator.comparing(OrgEmployee::getId));
        }

        Map<Long, Long> result = new HashMap<>();
        for (OrgEmployee e : all) {
            int rank = rankMap.get(e.getId());
            // 第 1 层没有上级
            if (rank <= com.caseflow.flow.OrgRank.TOP) {
                result.put(e.getId(), 0L);
                continue;
            }
            Long pid = e.getParentId();
            if (pid != null && pid > 0 && !pid.equals(e.getId()) && map.containsKey(pid)
                    && com.caseflow.flow.OrgRank.isParentOf(rankMap.get(pid), rank)) {
                result.put(e.getId(), pid);
                continue;
            }
            result.put(e.getId(), fallbackParent(rank, e, byRank));
        }
        return result;
    }

    /** 找不到合法上级时的兜底：同部门同层优先，其次全局同层，都没有则没有上级 */
    private Long fallbackParent(int rank, OrgEmployee self, Map<Integer, List<OrgEmployee>> byRank) {
        List<OrgEmployee> layer = byRank.get(rank - 1);
        if (layer == null || layer.isEmpty()) {
            return 0L;
        }
        String dept = self.getDept() == null ? "" : self.getDept().trim();
        if (!dept.isEmpty()) {
            for (OrgEmployee c : layer) {
                if (dept.equals(c.getDept() == null ? "" : c.getDept().trim())) {
                    return c.getId();
                }
            }
        }
        return layer.get(0).getId();
    }

    /** id -> 组织层级（由职务推导） */
    private Map<Long, Integer> rankMap(List<OrgEmployee> all) {
        Map<Long, Integer> map = new HashMap<>();
        for (OrgEmployee e : all) {
            map.put(e.getId(), com.caseflow.flow.OrgRank.of(e.getTitle()));
        }
        return map;
    }

    /** 部门 -> 该部门在职人数（空部门不计） */
    private Map<String, Integer> deptSizeMap(List<OrgEmployee> all) {
        Map<String, Integer> map = new HashMap<>();
        for (OrgEmployee e : all) {
            String d = e.getDept() == null ? "" : e.getDept().trim();
            if (!d.isEmpty()) {
                map.merge(d, 1, Integer::sum);
            }
        }
        return map;
    }

    /**
     * 标出「部门待核」的人：没填部门，或整个部门只有他一个人。
     * 组织树里用另一种颜色显示，提示管理员去补正。
     */
    private void markDeptAnomaly(Map<Long, EmployeeVO> voMap, Map<String, Integer> deptSize) {
        for (EmployeeVO vo : voMap.values()) {
            String d = vo.getDept() == null ? "" : vo.getDept().trim();
            boolean missing = d.isEmpty();
            Integer size = missing ? null : deptSize.get(d);
            boolean alone = size != null && size <= 1;
            vo.setDeptMissing(missing);
            vo.setDeptAlone(alone);
            vo.setDeptAnomaly(missing || alone);
        }
    }

    /**
     * 已有部门清单（新建/编辑员工时的部门下拉用）。
     *
     * <p>按人数降序、同人数按名称排——人一多就排在前面，
     * 下拉里最常见的几个部门自然排在最上面。
     */
    public List<Map<String, Object>> deptList() {
        Map<String, Integer> size = new LinkedHashMap<>();
        for (OrgEmployee e : employeeMapper.selectList(null)) {
            String d = Validators.trim(e.getDept());
            if (d != null && !d.isEmpty()) {
                size.merge(d, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Integer> en : size.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", en.getKey());
            m.put("count", en.getValue());
            list.add(m);
        }
        list.sort((a, b) -> {
            int c = Integer.compare((Integer) b.get("count"), (Integer) a.get("count"));
            return c != 0 ? c : String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name")));
        });
        return list;
    }

    private boolean markMatched(EmployeeVO node, String kw) {
        boolean self = hit(node, kw);
        boolean child = false;
        for (EmployeeVO c : node.getChildren()) {
            child = markMatched(c, kw) || child;
        }
        node.setMatched(self);
        return self || child;
    }

    private boolean hit(EmployeeVO node, String kw) {
        return contains(node.getName(), kw)
                || contains(node.getEmployeeNo(), kw)
                || contains(node.getDept(), kw)
                || contains(node.getTitle(), kw)
                || contains(node.getPhone(), kw);
    }

    private boolean contains(String v, String kw) {
        return v != null && v.toLowerCase().contains(kw);
    }

    private boolean keepBranch(EmployeeVO node) {
        if (Boolean.TRUE.equals(node.getMatched())) {
            return true;
        }
        List<EmployeeVO> kept = node.getChildren().stream().filter(this::keepBranch).collect(Collectors.toList());
        node.setChildren(kept);
        return !kept.isEmpty();
    }

    /** 扁平检索结果（指派抽屉里用，带完整所属链路） */
    public List<EmployeeVO> search(String keyword, Integer limit) {
        List<EmployeeVO> flat = tree(null, 1).stream()
                .flatMap(this::flatten)
                .collect(Collectors.toList());
        List<EmployeeVO> list = flat;
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim().toLowerCase();
            list = flat.stream().filter(v -> hit(v, kw)).collect(Collectors.toList());
        }
        if (limit != null && limit > 0 && list.size() > limit) {
            list = new ArrayList<>(list.subList(0, limit));
        }
        return list;
    }

    private java.util.stream.Stream<EmployeeVO> flatten(EmployeeVO node) {
        if (node.getChildren() == null || node.getChildren().isEmpty()) {
            return java.util.stream.Stream.of(node);
        }
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(node),
                node.getChildren().stream().flatMap(this::flatten));
    }

    /**
     * 注册页「认领我的员工档案」候选名单。对应 {@code GET /auth/register/employees}，
     * 该接口无需登录（注册页还没有账号），所以这里刻意只回传
     * id / 姓名 / 工号 / 部门 / 职务 / 所属链路，<b>不带手机号、邮箱</b>，避免匿名枚举联系人信息。
     */
    public List<EmployeeVO> registerCandidates(String keyword, Integer limit) {
        List<EmployeeVO> flat = tree(null, 1).stream()
                .flatMap(this::flatten)
                .collect(Collectors.toList());
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim().toLowerCase();
            flat = flat.stream().filter(v -> hit(v, kw)).collect(Collectors.toList());
        }
        int cap = (limit == null || limit <= 0) ? 20 : Math.min(limit, 50);
        if (flat.size() > cap) {
            flat = new ArrayList<>(flat.subList(0, cap));
        }
        return flat.stream().map(v -> {
            EmployeeVO t = new EmployeeVO();
            t.setId(v.getId());
            t.setName(v.getName());
            t.setEmployeeNo(v.getEmployeeNo());
            t.setDept(v.getDept());
            t.setTitle(v.getTitle());
            t.setPathName(v.getPathName());
            t.setStatus(v.getStatus());
            return t;
        }).collect(Collectors.toList());
    }

    /**
     * 就地建档：注册时本人自建档案（origin=SELF_REGISTER），或管理员在账号弹窗里快速新增（origin=MANUAL）。
     *
     * <p>与 {@link #save(EmployeeSaveRequest)} 的区别：只收最少的字段、不要求登录态，
     * 上级允许留空（落到顶层，管理员后续可在员工图谱里调整）。
     *
     * <p>调用方（注册 / 建号）本身也是事务方法，此处返回的实体尚未提交，
     * 一旦外层回滚，档案会一起回滚，不会留下孤儿档案。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrgEmployee createBrief(EmployeeBrief brief, String origin) {
        if (brief == null) {
            throw new BizException("请填写员工档案信息");
        }
        String name = Validators.trim(brief.getName());
        if (name == null || name.isEmpty()) {
            throw new BizException("员工姓名不能为空");
        }
        if (name.length() > 64) {
            throw new BizException("员工姓名过长");
        }
        String phone = Validators.trim(brief.getPhone());
        if (phone != null && !phone.isEmpty() && !Validators.isPhone(phone)) {
            throw new BizException("员工手机号格式不正确，应为 11 位数字");
        }
        Long parentId = brief.getParentId() == null ? 0L : brief.getParentId();
        if (parentId > 0 && employeeMapper.selectById(parentId) == null) {
            throw new BizException("所选上级不存在，请重新选择");
        }

        LocalDateTime now = LocalDateTime.now();
        OrgEmployee e = new OrgEmployee();
        e.setName(name);
        e.setEmployeeNo(Validators.trim(brief.getEmployeeNo()));
        e.setParentId(parentId);
        e.setDept(Validators.trim(brief.getDept()));
        e.setTitle(Validators.trim(brief.getTitle()));
        e.setPhone(phone);
        e.setLevelNo(1);
        e.setSortNo(0);
        e.setStatus(1);
        e.setOrigin(origin == null ? "MANUAL" : origin);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        employeeMapper.insert(e);
        rebuildTree();
        logService.log("EMPLOYEE", "CREATE", "EMPLOYEE", e.getId(),
                ("SELF_REGISTER".equals(e.getOrigin()) ? "注册时自建员工档案：" : "快速新增员工：") + e.getName()
                        + (e.getDept() == null ? "" : "（" + e.getDept() + "）"));
        return e;
    }

    private EmployeeVO toVO(OrgEmployee e, Map<Long, Integer> activeCount) {
        EmployeeVO vo = new EmployeeVO();
        vo.setId(e.getId());
        vo.setName(e.getName());
        vo.setEmployeeNo(e.getEmployeeNo());
        vo.setParentId(e.getParentId());
        vo.setDept(e.getDept());
   vo.setTitle(e.getTitle());
        int rank = com.caseflow.flow.OrgRank.of(e.getTitle());
        vo.setRank(rank);
        vo.setRankLabel(com.caseflow.flow.OrgRank.label(rank));
    String pg = com.caseflow.flow.PoliceGroup.normalize(e.getPoliceGroup());
        vo.setPoliceGroup(pg);
    vo.setPoliceGroupName(com.caseflow.flow.PoliceGroup.label(pg));
        vo.setPhone(e.getPhone());
        vo.setEmail(e.getEmail());
        vo.setLevelNo(e.getLevelNo());
        vo.setSortNo(e.getSortNo());
        vo.setStatus(e.getStatus());
        vo.setOrigin(e.getOrigin());
        Integer c = activeCount.get(e.getId());
        vo.setActiveCaseCount(c == null ? 0 : c);
        return vo;
    }

    /** 每个人当前在手（ACTIVE）案件数 */
    private Map<Long, Integer> activeCaseCount() {
        Map<Long, Integer> count = new HashMap<>();
        List<CaseAssignee> list = assigneeMapper.selectList(
                new LambdaQueryWrapper<CaseAssignee>().eq(CaseAssignee::getStatus, "ACTIVE"));
        for (CaseAssignee a : list) {
            count.merge(a.getEmployeeId(), 1, Integer::sum);
        }
        return count;
    }

    public EmployeeVO detail(Long id) {
        OrgEmployee e = employeeMapper.selectById(id);
        if (e == null) {
            throw new BizException("员工不存在");
        }
        Map<Long, String> paths = pathNameMap();
        EmployeeVO vo = toVO(e, activeCaseCount());
        vo.setPathName(paths.get(id));
        return vo;
    }

    // ------------------------------------------------------------------
    // 维护
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public EmployeeVO save(EmployeeSaveRequest req) {
        OrgEmployee entity = new OrgEmployee();
        entity.setName(req.getName().trim());
        entity.setEmployeeNo(req.getEmployeeNo());
        entity.setDept(req.getDept());
        entity.setTitle(req.getTitle());
        // 办案组别：空/非法一律归一为「不限」，不因漏填卡住员工档案录入
        entity.setPoliceGroup(com.caseflow.flow.PoliceGroup.normalize(req.getPoliceGroup()));
        entity.setPhone(req.getPhone());
        entity.setEmail(req.getEmail());
        entity.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        entity.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        entity.setParentId(req.getParentId() == null ? 0L : req.getParentId());
        LocalDateTime now = LocalDateTime.now();

        if (req.getId() == null) {
            if (entity.getParentId() > 0 && employeeMapper.selectById(entity.getParentId()) == null) {
                throw new BizException("上级不存在");
            }
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            entity.setOrigin("MANUAL");
            employeeMapper.insert(entity);
            logService.log("EMPLOYEE", "CREATE", "EMPLOYEE", entity.getId(), "新增员工：" + entity.getName());
        } else {
            OrgEmployee old = employeeMapper.selectById(req.getId());
            if (old == null) {
                throw new BizException("员工不存在");
            }
            if (entity.getParentId().equals(req.getId())) {
                throw new BizException("上级不能是自己");
            }
            if (entity.getParentId() > 0 && isDescendant(entity.getParentId(), req.getId())) {
                throw new BizException("不能把上级设成自己的下级（会形成环）");
            }
            entity.setId(req.getId());
            entity.setCreatedAt(old.getCreatedAt());
            entity.setUpdatedAt(now);
            employeeMapper.updateById(entity);
            logService.log("EMPLOYEE", "UPDATE", "EMPLOYEE", entity.getId(), "修改员工：" + entity.getName());
        }
        rebuildTree();
        return detail(entity.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        OrgEmployee e = employeeMapper.selectById(id);
        if (e == null) {
            throw new BizException("员工不存在");
        }
        long children = employeeMapper.selectCount(new LambdaQueryWrapper<OrgEmployee>().eq(OrgEmployee::getParentId, id));
        if (children > 0) {
            throw new BizException("该员工下仍有 " + children + " 名下级，请先调整组织关系");
        }
        long active = assigneeMapper.selectCount(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getEmployeeId, id).eq(CaseAssignee::getStatus, "ACTIVE"));
        if (active > 0) {
            throw new BizException("该员工仍有 " + active + " 个在手案件，请先改派或办理完毕");
        }
        employeeMapper.deleteById(id);
        logService.log("EMPLOYEE", "DELETE", "EMPLOYEE", id, "删除员工：" + e.getName());
    }

    private boolean isDescendant(Long maybeChild, Long ancestorId) {
        Long cursor = maybeChild;
        int guard = 0;
        while (cursor != null && cursor > 0 && guard++ < 100) {
            if (cursor.equals(ancestorId)) {
                return true;
            }
            OrgEmployee e = employeeMapper.selectById(cursor);
            if (e == null || e.getParentId() == null) {
                return false;
            }
            cursor = e.getParentId();
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Excel 导入 / 模板
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public ImportResultVO importExcel(MultipartFile file) {
        ImportResultVO result = new ImportResultVO();
        List<String> errors = new ArrayList<>();
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要导入的 Excel 文件");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!original.endsWith(".xls") && !original.endsWith(".xlsx") && !original.endsWith(".csv")) {
            throw new BizException("仅支持 .xls / .xlsx / .csv 文件");
        }
        EmployeeExcelListener listener = new EmployeeExcelListener();
        try (InputStream in = file.getInputStream()) {
            EasyExcel.read(in, EmployeeExcelRow.class, listener).sheet().doRead();
        } catch (IOException e) {
            throw new BizException("文件读取失败：" + e.getMessage());
        }
        List<EmployeeExcelRow> rows = listener.getRows();
        result.setTotal(rows.size());
        if (rows.isEmpty()) {
            throw new BizException("未解析到任何有效数据行");
        }

        LocalDateTime now = LocalDateTime.now();
        // 已存在的工号 -> 更新，否则新增（重复导入幂等）
        Map<String, OrgEmployee> byNo = new HashMap<>();
        for (OrgEmployee e : employeeMapper.selectList(null)) {
            if (e.getEmployeeNo() != null && !e.getEmployeeNo().isEmpty()) {
                byNo.put(e.getEmployeeNo(), e);
            }
        }

        int success = 0;
        for (int i = 0; i < rows.size(); i++) {
            EmployeeExcelRow row = rows.get(i);
            int lineNo = i + 2; // 表头占第 1 行
            if (row.getEmployeeNo() == null || row.getEmployeeNo().isEmpty()) {
                errors.add("第 " + lineNo + " 行：缺少工号，跳过");
                continue;
            }
            try {
                OrgEmployee entity = byNo.get(row.getEmployeeNo());
                boolean isNew = entity == null;
                if (isNew) {
                    entity = new OrgEmployee();
                    entity.setCreatedAt(now);
                }
                entity.setName(row.getName());
                entity.setEmployeeNo(row.getEmployeeNo());
                entity.setParentNo(row.getParentNo());
                entity.setDept(row.getDept());
                entity.setTitle(row.getTitle());
                entity.setPhone(row.getPhone());
                entity.setEmail(row.getEmail());
                entity.setStatus(1);
                entity.setSortNo(i);
                entity.setUpdatedAt(now);
                if (isNew) {
                    entity.setParentId(0L);
                    entity.setIdPath("/");
                    employeeMapper.insert(entity);
                    byNo.put(entity.getEmployeeNo(), entity);
                } else {
                    employeeMapper.updateById(entity);
                }
                success++;
            } catch (Exception ex) {
                errors.add("第 " + lineNo + " 行写入失败：" + ex.getMessage());
            }
        }

        // 二次回填：parent_no -> parent_id
        for (OrgEmployee e : employeeMapper.selectList(null)) {
            if (e.getParentNo() != null && !e.getParentNo().isEmpty()) {
                OrgEmployee parent = byNo.get(e.getParentNo());
                if (parent != null && !parent.getId().equals(e.getId())) {
                    e.setParentId(parent.getId());
                } else if (parent != null) {
                    e.setParentId(0L);
                    errors.add("工号 " + e.getEmployeeNo() + " 的上级指向了自己，已置为顶层");
                } else {
                    e.setParentId(0L);
                    errors.add("工号 " + e.getEmployeeNo() + " 的上级工号 " + e.getParentNo() + " 不存在，已置为顶层");
                }
                employeeMapper.updateById(e);
            }
        }
        rebuildTree();
        result.setSuccess(success);
        result.setFailed(rows.size() - success);
        result.setErrors(errors);
        logService.log("EMPLOYEE", "IMPORT", "EMPLOYEE", null,
                "导入员工图谱：共 " + rows.size() + " 行，成功 " + success + " 行");
        return result;
    }

    /**
     * 重算 level_no / id_path，并按职务层级<b>回写 parent_id</b>。
     *
     * <p>2026-10-08：层级不再是「谁连谁」的自由连线，而是总-副总-组长-员工四层。
     * 这里把解析出来的真实上级写回库，所以下一次打开组织树、导出、检索
     * 看到的都是同一套层级，不会出现「库里一层、界面上另一层」。
     */
    private void rebuildTree() {
        List<OrgEmployee> all = employeeMapper.selectList(null);
        Map<Long, OrgEmployee> map = idMap(all);
        Map<Long, Integer> rankMap = rankMap(all);
        Map<Long, Long> parentOf = resolveParents(all, rankMap);
        for (OrgEmployee e : all) {
            List<Long> chain = new ArrayList<>();
            Long cursor = e.getId();
            Set<Long> guard = new HashSet<>();
            while (cursor != null && cursor > 0 && map.containsKey(cursor) && guard.add(cursor)) {
                guard.add(cursor);
                chain.add(0, cursor);
                cursor = parentOf.get(cursor);
            }
            e.setParentId(parentOf.getOrDefault(e.getId(), 0L));
            e.setLevelNo(chain.isEmpty() ? 1 : chain.size());
            StringBuilder sb = new StringBuilder("/");
            for (Long id : chain) {
                sb.append(id).append("/");
            }
            e.setIdPath(sb.toString());
            employeeMapper.updateById(e);
        }
    }

    /** 下载导入模板 */
    public void writeTemplate(HttpServletResponse response) throws IOException {
        List<EmployeeExcelRow> demo = new ArrayList<>(Arrays.asList(
                row("王总", "E001", "", "刑事侦查大队", "领导", "13800000001", "wang@example.com"),
                row("李副总", "E002", "E001", "刑事侦查大队", "副领导", "13800000002", "li@example.com"),
                row("张组长", "E003", "E002", "一组", "组长", "13800000003", "zhang@example.com"),
                row("陈组员", "E004", "E003", "一组", "组员", "13800000004", "chen@example.com")
        ));
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String name = URLEncoder.encode("员工图谱导入模板", "UTF-8").replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename=" + name + ".xlsx");
        EasyExcel.write(response.getOutputStream(), EmployeeExcelRow.class).sheet("员工图谱").doWrite(demo);
    }

    private EmployeeExcelRow row(String name, String no, String parentNo, String dept, String title, String phone, String email) {
        EmployeeExcelRow r = new EmployeeExcelRow();
        r.setName(name);
        r.setEmployeeNo(no);
        r.setParentNo(parentNo);
        r.setDept(dept);
        r.setTitle(title);
        r.setPhone(phone);
        r.setEmail(email);
        return r;
    }
}
