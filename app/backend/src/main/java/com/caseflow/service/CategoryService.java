package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseCategory;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseCategoryMapper;
import com.caseflow.support.DictHolder;
import com.caseflow.vo.CategoryVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 案件类别（小类）字典：管理权限账户可增删改查，前端级联选择器据此构建分类树。
 * 大类固定为 刑事 / 行政 / 未立案（未立案不设小类）。
 */
@Service
public class CategoryService {

    /** 大类固定顺序（与前端图例、字典展示保持一致） */
    private static final List<String> TYPE_ORDER = Arrays.asList("CRIMINAL", "ADMINISTRATIVE", "PRELIMINARY");

    @Resource
    private CaseCategoryMapper categoryMapper;

    /** 级联树：大类 → 小类；未立案无小类返回空 children */
    public List<CategoryVO.Node> tree() {
        List<CaseCategory> all = categoryMapper.selectList(
                new LambdaQueryWrapper<CaseCategory>().orderByAsc(CaseCategory::getSort));
        return TYPE_ORDER.stream().map(t -> {
            CategoryVO.Node node = new CategoryVO.Node();
            node.setValue(t);
            node.setLabel(DictHolder.name("CASE_TYPE", t));
            node.setChildren(all.stream()
                    .filter(c -> t.equals(c.getCaseType()))
                    .map(c -> {
                        CategoryVO.Node child = new CategoryVO.Node();
                        child.setValue(c.getName());
                        child.setLabel(c.getName());
                        return child;
                    })
                    .collect(Collectors.toList()));
            return node;
        }).collect(Collectors.toList());
    }

    /** 管理列表：平铺全部小类（含 id） */
    public List<CategoryVO.Item> list() {
        List<CaseCategory> all = categoryMapper.selectList(
                new LambdaQueryWrapper<CaseCategory>().orderByAsc(CaseCategory::getCaseType)
                        .orderByAsc(CaseCategory::getSort));
        return all.stream().map(this::toItem).collect(Collectors.toList());
    }

    public CategoryVO.Item add(String caseType, String name) {
        String type = normalizeType(caseType);
        String n = normalizeName(name);
        checkDuplicate(type, n, null);

        CaseCategory c = new CaseCategory();
        c.setCaseType(type);
        c.setName(n);
        c.setSort(nextSort(type));
        categoryMapper.insert(c);
        return toItem(c);
    }

    public CategoryVO.Item update(Long id, String caseType, String name, Integer sort) {
        CaseCategory c = categoryMapper.selectById(id);
        if (c == null) {
            throw new BizException("该类别不存在或已被删除");
        }
        String type = normalizeType(caseType);
        String n = normalizeName(name);
        checkDuplicate(type, n, id);

        c.setCaseType(type);
        c.setName(n);
        if (sort != null) {
            c.setSort(sort);
        }
        categoryMapper.updateById(c);
        return toItem(c);
    }

    public void delete(Long id) {
        CaseCategory c = categoryMapper.selectById(id);
        if (c == null) {
            return;
        }
        categoryMapper.deleteById(id);
    }

    // ------------------------------------------------------------------

    private String normalizeType(String caseType) {
        if (!StringUtils.hasText(caseType)) {
            throw new BizException("请选择所属大类");
        }
        String t = caseType.trim();
        if (!TYPE_ORDER.contains(t)) {
            throw new BizException("大类不合法");
        }
        return t;
    }

    private String normalizeName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BizException("请输入小类名称");
        }
        String n = name.trim();
        if (n.length() > 64) {
            throw new BizException("名称过长（最多 64 字）");
        }
        return n;
    }

    private void checkDuplicate(String caseType, String name, Long excludeId) {
        List<CaseCategory> dup = categoryMapper.selectList(new LambdaQueryWrapper<CaseCategory>()
                .eq(CaseCategory::getCaseType, caseType).eq(CaseCategory::getName, name));
        boolean conflict = excludeId == null
                ? !dup.isEmpty()
                : dup.stream().anyMatch(c -> !c.getId().equals(excludeId));
        if (conflict) {
            throw new BizException("该大类下已存在同名小类");
        }
    }

    private int nextSort(String caseType) {
        List<CaseCategory> list = categoryMapper.selectList(new LambdaQueryWrapper<CaseCategory>()
                .eq(CaseCategory::getCaseType, caseType));
        return list.stream().map(c -> c.getSort() == null ? 0 : c.getSort())
                .max(Comparator.naturalOrder()).orElse(0) + 1;
    }

    private CategoryVO.Item toItem(CaseCategory c) {
        CategoryVO.Item item = new CategoryVO.Item();
        item.setId(c.getId());
        item.setCaseType(c.getCaseType());
        item.setCaseTypeName(DictHolder.name("CASE_TYPE", c.getCaseType()));
        item.setName(c.getName());
        item.setSort(c.getSort());
        return item;
    }
}
