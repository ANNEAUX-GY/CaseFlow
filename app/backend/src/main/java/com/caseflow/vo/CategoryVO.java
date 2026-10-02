package com.caseflow.vo;

import lombok.Data;

import java.util.List;

/**
 * 案件类别（小类）字典的视图对象。
 */
public class CategoryVO {

    /** 级联树节点：大类 → 小类（前端 el-cascader 直接用） */
    @Data
    public static class Node {
        private String value;
        private String label;
        private List<Node> children;
    }

    /** 管理列表项（平铺，含 id 供增删改定位） */
    @Data
    public static class Item {
        private Long id;
        private String caseType;
        private String caseTypeName;
        private String name;
        private Integer sort;
    }
}
