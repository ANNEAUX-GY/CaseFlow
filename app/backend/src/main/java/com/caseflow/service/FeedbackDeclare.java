package com.caseflow.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行记录句的解析与拼装（2026-10-08 建，2026-10-13 改口径）。
 *
 * <p><b>为什么要有这个类</b>：记录句「于 X 在 Y 执行了 Z 操作。」原先是拼进
 * {@code case_todo_feedback.content} 的一整句话。用户要求能单独改
 * 「执行平台 / 操作名」，拼在一句里就只能整句重写——所以拆成三列。
 * 拆出来的历史数据要还原成列、新的又要能拼回一句给人看，
 * 两个方向的格式必须**只有这一处定义**，否则改了一处另一处就漂移。
 *
 * <p>2026-10-13 文案由「上传了…文件」改为「执行了…操作」——实际用法多是
 * 在某个系统里做了个操作，并没有上传文件。**字段名仍是 upload_***（改列名要动
 * schema + 迁移 + 历史数据，收益不抵风险），只有用户看到的文案改了。
 *
 * <p><b>顺序有讲究</b>：先判"文件"再判"平台"。平台名和文件名都可能含
 * 「在」「于」等字（例如「全国公安信息网」），先抓平台会把后面的时间/文件吃掉。
 * 这里靠"平台不换行、文件可含标点"的差异，先用较宽松的规则吃掉文件名。
 */
public final class FeedbackDeclare {

    private FeedbackDeclare() {
    }

    /**
     * 匹配声明句。
     *
     * <p><b>新旧两种句式都要认</b>（2026-10-13）：
     * <ul>
     *   <li>新：{@code 于 <时间> 在 <系统> 执行了 <操作> 操作。}</li>
     *   <li>旧：{@code 于 <时间> 在 <平台> 上传了 <文件>。}——历史数据里是这个，
     *       回填脚本与读侧兜底解析都靠它拆列，不能只留新句式。</li>
     * </ul>
     *
     * <p>新句式末尾多一个「操作」，用可选分组吃掉，且<b>操作名本身也可能以「操作」二字结尾</b>
     * （如「执行了查询操作 操作。」虽然别扭但用户真能填出来），所以这里把结尾的
     * 「操作」也做成可选，交给 group(3) 原样带出去，不做去重处理——
     * 宁可多留两个字，也不能把用户填的内容改坏。
     *
     * <p>时间允许 {@code 2026-10-18}（精度到天，2026-10-18 起的唯一写法）、
     * 也兼容历史数据里的 {@code 2026-10-08 09:58:42} 与 {@code 2026-10-08T09:58:42}
     * （前端 date-picker 的 value-format 以前带时分秒，存量内容不能拆不回来）；
     * 还接受占位符「（未填时间）」——{@link #sentence} 会用它拼装，而拼装结果可能被写进
     * {@code content} 再拆回来，两边口径必须一致，否则<b>时间为空时往返即断</b>
     * （拆不回 → 修改弹窗回填丢内容）。
     * 名称用非贪婪匹配到句号为止，允许中间有空格和标点。
     */
    private static final Pattern DECLARE = Pattern.compile(
            "于\\s*([0-9]{4}-[0-9]{2}-[0-9]{2}(?:[ T][0-9]{2}:[0-9]{2}(?::[0-9]{2})?)?|（未填时间）)"
                    + "\\s*在\\s*(.+?)"
                    + "\\s*(?:上传了|执行了)\\s*(.+?)(?:操作)?。");

    /**
     * 从落实说明里拆出执行记录。
     *
     * <p><b>占位符要还原成空串</b>：{@link #sentence} 在时间为空时会拼出
     * 「（未填时间）」这个占位符，这里若原样返回，调用方会把它当真实时间存进
     * {@code upload_time} 列——界面上就会显示一条根本没填过的时间。
     * 同理平台/操作为空时的「（待填平台）」「（待填操作）」。
     *
     * @return {@code [时间, 平台, 操作, 去掉记录句后的剩余说明]}；
     *         没有记录句时返回 {@code null}（调用方按"无记录"处理，别当成空数组——
     *         空数组会被误判成"有记录但三项都空"）。
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
                denull(m.group(1)),
                denull(m.group(2)),
                denull(m.group(3)),
                rest.trim()
        };
    }

    /** 占位符还原为空串，其余 trim */
    private static String denull(String s) {
        String t = trim(s);
        return PLACEHOLDERS.contains(t) ? "" : t;
    }

    /** {@link #sentence} 会用到的占位符，与正则里的字面量必须一致 */
    private static final Set<String> PLACEHOLDERS = new HashSet<>(
            Arrays.asList("（未填时间）", "（待填平台）", "（待填操作）"));

    /**
     * 三要素拼成一句记录（展示用）。任一项为空返回空串——残缺的记录句读起来别扭。
     *
     * <p>与 {@link #DECLARE} 必须同口径：这里拼出来的新句式，正则要能拆回去。
     * 新增句式时两边一起改，否则「拼得出、拆不回」，修改弹窗回填就会丢内容。
     */
    public static String sentence(String time, String platform, String file) {
        String t = trim(time);
        String p = trim(platform);
        String f = trim(file);
        if (p.isEmpty() && f.isEmpty()) {
            return "";
        }
        return "于 " + (t.isEmpty() ? "（未填时间）" : t) + " 在 "
                + (p.isEmpty() ? "（待填平台）" : p) + " 执行了 "
                + (f.isEmpty() ? "（待填操作）" : f) + " 操作。";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
