package org.zhzssp.memorandum.agenteval.cost;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 价目表：模型 → 三档单价（输入未命中 / 输入命中缓存 / 输出），单位美元每百万 token。
 *
 * <h3>为什么价目表是一份<b>资源文件</b>，而不是代码里的常量</h3>
 * 价格是<b>外部事实</b>，会在与代码无关的时刻变动。写成常量的话，
 * 改价就得改代码、过 review、跑构建——于是没人会去改，价目表慢慢变成一份没人信的化石数字。
 * 放成资源文件，改价是一次纯数据 diff，谁改的、什么时候改的、改成多少，git 里一目了然。
 *
 * <h3>★ 价目不准会怎样：几乎不怎样</h3>
 * 这点必须说清楚，否则 {@code costUsd} 这个字段会被过度解读。
 * 成本数字有两个用途，对价格准确度的要求完全不同：
 * <ul>
 *   <li><b>门禁</b>（"这次改动让成本涨了多少"）——比的是同一份价表下的<b>比值</b>，
 *       价格整体偏高或偏低会在分子分母里<b>约掉</b>。这个用途对价格准确度几乎不敏感。</li>
 *   <li><b>直觉</b>（"跑一次全套评测大概几分钱"）——这个才需要价格接近真实，
 *       而它本来也只用于量级感知，不是记账。</li>
 * </ul>
 * 所以：<b>不要拿这里的数字去对账单</b>，但可以放心拿它做前后对比。
 *
 * <h3>为什么未知模型走「最贵档」而不是 0</h3>
 * 按 0 计价意味着换个模型名，成本门禁就<b>静默失效</b>了——报告上是一个漂亮的 $0.0000。
 * 这与「上游没返回 usage 就当 0」是同一类错误：<b>把未知当成没有</b>。
 * 宁可高估到刺眼，也不要低估到无人察觉。
 */
public final class PriceTable {

    private static final String RESOURCE = "/agent-eval/pricing.json";

    private static final ObjectMapper OM = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 三档单价，美元每百万 token。 */
    public record Rate(double inputCacheMiss, double inputCacheHit, double output) {}

    private final String asOf;
    private final Map<String, Rate> models;
    private final Rate fallback;

    private PriceTable(String asOf, Map<String, Rate> models, Rate fallback) {
        this.asOf = asOf;
        this.models = models;
        this.fallback = fallback;
    }

    public static PriceTable load() {
        try (InputStream in = PriceTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("缺少价目表资源：" + RESOURCE);
            JsonNode root = OM.readTree(in);
            Map<String, Rate> models = new LinkedHashMap<>();
            JsonNode ms = root.path("models");
            ms.fieldNames().forEachRemaining(name -> models.put(name, rate(ms.path(name))));
            return new PriceTable(root.path("asOf").asText("unknown"),
                    models, rate(root.path("fallback")));
        } catch (Exception e) {
            throw new IllegalStateException("加载价目表失败：" + RESOURCE, e);
        }
    }

    /** 供单测构造确定性价表，避免测试断言随真实价格变动而失败。 */
    public static PriceTable of(Map<String, Rate> models, Rate fallback) {
        return new PriceTable("test", new LinkedHashMap<>(models), fallback);
    }

    private static Rate rate(JsonNode n) {
        return new Rate(
                n.path("inputCacheMiss").asDouble(0),
                n.path("inputCacheHit").asDouble(0),
                n.path("output").asDouble(0));
    }

    public String asOf() {
        return asOf;
    }

    /** 模型未登记时返回 fallback（最贵档），绝不返回零价。 */
    public Rate rateFor(String model) {
        if (model == null) return fallback;
        Rate r = models.get(model);
        return r != null ? r : fallback;
    }

    /** 该模型是否登记在册。报告里要把"按 fallback 计价"标出来，否则数字会被误读为实价。 */
    public boolean isKnown(String model) {
        return model != null && models.containsKey(model);
    }
}
