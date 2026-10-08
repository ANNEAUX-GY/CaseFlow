package com.caseflow.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上传声明句的解析与拼装（2026-10-08）。
 *
 * <p><b>为什么要有这个类</b>：声明句「于 X 在 Y 上传了 Z。」原先是拼进
 * {@code case_todo_feedback.content} 的一整句话。用户要求能单独改
 * 「上传平台 / 上传文件名」，拼在一句里就只能整句重写——所以拆成三列。
 * 拆出来的历史数据要还原成列、新的又要能拼回一句给人看，
 * 两个方向的格式必须**只有这一处定义**，否则改了一处另一处就漂移。
 *
 * <p><b>顺序有讲究</b>：先判"文件"再判"平台"。平台名和文件名都可能含
 * 「在」「于」等字（例如「全国公安信息网」），先抓平台会把后面的时间/文件吃掉。
 * 这里靠"平台不换行、文件可含标点"的差异，先用较宽松的规则吃掉文件名。
 */
public final class FeedbackDeclare {

    private FeedbackDeclare() {
    }

    /**
     * 匹配声明句：{@code 于 <时间> 在 <平台> 上传了 <文件>。}
     *
     * <p>时间允许 {@code 2026-10-08 09:58:42} 与 {@code 2026-10-08T09:58:42} 两种分隔符
     * （前端 date-picker 的 value-format 两种都可能出现）。
     * 文件名用非贪婪匹配到句号为止，允许中间有空格和标点（"调取监控情况说明.docx"）。
     */
    private static final Pattern DECLARE = Pattern.compile(
            "于\\s*([0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}(?::[0-9]{2})?)"
                    + "\\s*在\\s*(.+?)"
                    + "\\s*上传了\\s*(.+?)。");

    /**
     * 从落实说明里拆出上传声明。
     *
     * @return {@code [时间, 平台, 文件, 去掉声明句后的剩余说明]}；
     *         没有声明句时返回 {@code null}（调用方按"无声明"处理，别当成空数组——
     *         空数组会被误判成"有声明但三项都空"）。
     */
    public static String[] parse(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        Matcher m = DECLARE.matcher(content);
        if (!m.find()) {
            return null;
        }
        String rest = content.substring(0, m.start()) + content.substring(m.end());
        return new String[]{
                m.group(1).trim(),
                m.group(2).trim(),
                m.group(3).trim(),
                rest.trim()
        };
    }

    /** 三要素拼成一句声明（展示用）。任一项为空返回空串——残缺的声明句读起来别扭 */
    public static String sentence(String time, String platform, String file) {
        String t = trim(time);
        String p = trim(platform);
        String f = trim(file);
        if (p.isEmpty() && f.isEmpty()) {
            return "";
        }
        return "于 " + (t.isEmpty() ? "（未填时间）" : t) + " 在 "
                + (p.isEmpty() ? "（未填平台）" : p) + " 上传了 "
                + (f.isEmpty() ? "（未填文件）" : f) + "。";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
