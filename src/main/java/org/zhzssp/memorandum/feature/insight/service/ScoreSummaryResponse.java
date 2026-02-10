package org.zhzssp.memorandum.feature.insight.service;

/**
 * AI 总结接口的响应体。
 */
public class ScoreSummaryResponse {

    private String summary;

    public ScoreSummaryResponse() {
    }

    public ScoreSummaryResponse(String summary) {
        this.summary = summary;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }
}

