package org.zhzssp.memorandum.agenteval.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.zhzssp.memorandum.agenteval.AgentEvalBase;
import org.zhzssp.memorandum.agenteval.trial.EvalTrial;
import org.zhzssp.memorandum.feature.agent.memory.FactService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Facts 抽取质量：真实 LLM（经录制盒），量准不准，不量入库。
 *
 * <p>facts 默认关闭，正是因为以前没有这组数字。本用例给出数字；
 * <b>低于门槛不得打开开关</b>——打开却不达标会在这里判红。</p>
 */
@Tag("agent-eval-capability")
@DisplayName("Facts 抽取质量")
class FactExtractionEvalTest extends AgentEvalBase {

    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    FactService factService;

    @Value("${agent.context.facts.enabled:false}")
    boolean factsEnabled;

    @EvalTrial
    @DisplayName("facts_extraction_accuracy")
    void facts_extraction_accuracy() throws Exception {
        JsonNode root = OM.readTree(FactExtractionEvalTest.class
                .getResourceAsStream("/agent-eval/facts/extraction-golden.json"));
        assertNotNull(root, "找不到抽取金标");
        double minP = root.path("minPrecision").asDouble(0.65);
        double minR = root.path("minRecall").asDouble(0.50);

        ExtractionMetrics.Score total = new ExtractionMetrics.Score(0, 0, 0);
        List<Map<String, Object>> perSample = new ArrayList<>();
        for (JsonNode sample : root.path("samples")) {
            String id = sample.path("id").asText();
            String input = sample.path("input").asText();
            List<List<String>> expect = new ArrayList<>();
            for (JsonNode group : sample.path("expect")) {
                List<String> tokens = new ArrayList<>();
                group.forEach(n -> tokens.add(n.asText()));
                expect.add(tokens);
            }
            List<FactService.ExtractedFact> extracted = factService.previewExtract(input);
            List<String> values = extracted.stream().map(FactService.ExtractedFact::value).toList();
            ExtractionMetrics.Score s = ExtractionMetrics.score(values, expect);
            total = ExtractionMetrics.plus(total, s);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("extracted", extracted);
            row.put("tp", s.tp());
            row.put("fp", s.fp());
            row.put("fn", s.fn());
            perSample.add(row);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("precision", total.precision());
        report.put("recall", total.recall());
        report.put("tp", total.tp());
        report.put("fp", total.fp());
        report.put("fn", total.fn());
        report.put("minPrecision", minP);
        report.put("minRecall", minR);
        report.put("meetsGate", total.precision() >= minP && total.recall() >= minR);
        report.put("factsEnabled", factsEnabled);
        report.put("hint", factsEnabled
                ? "开关已开：必须达到门槛，否则每一轮 system prompt 都会被错 fact 污染"
                : "开关仍关：本报告只提供数字，不因此打开 facts");
        report.put("samples", perSample);

        Path out = Path.of("build/agent-eval/facts-extraction.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, OM.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("[AgentEval] Facts 抽取  P=" + total.precision()
                + "  R=" + total.recall()
                + "  门槛 P≥" + minP + " R≥" + minR
                + "  达标=" + report.get("meetsGate")
                + "  已写入 " + out.toAbsolutePath());

        if (factsEnabled) {
            assertFalse(total.precision() < minP || total.recall() < minR,
                    "facts 已打开，但抽取未达标 P=" + total.precision()
                            + " R=" + total.recall() + "（门槛 P≥" + minP + " R≥" + minR + "）。关回去。");
        }
    }
}
