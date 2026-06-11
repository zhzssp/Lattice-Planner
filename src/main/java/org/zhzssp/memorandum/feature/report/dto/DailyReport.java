package org.zhzssp.memorandum.feature.report.dto;

/**
 * 主动式 Agent 推送的「晨报 / 晚报」数据载体。
 *
 * <p>字段约定（直接序列化给 Electron 客户端消费）：</p>
 * <ul>
 *   <li>{@code type}    —— "morning" / "evening" / "none"，none 表示当前无需推送</li>
 *   <li>{@code title}   —— 系统通知标题（简短）</li>
 *   <li>{@code body}    —— 系统通知正文（一句话摘要，弹窗展示）</li>
 *   <li>{@code detail}  —— 完整 Markdown 文本（点击通知后在应用内可查看的全文）</li>
 *   <li>{@code generatedAt} —— 生成时刻 ISO 字符串，便于客户端去重</li>
 * </ul>
 */
public record DailyReport(
        String type,
        String title,
        String body,
        String detail,
        String generatedAt
) {
    /** 当前时间窗内无可推送内容时的占位响应。 */
    public static DailyReport none() {
        return new DailyReport("none", null, null, null, null);
    }

    public boolean isNone() {
        return type == null || "none".equals(type);
    }
}
