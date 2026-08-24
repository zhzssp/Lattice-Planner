package org.zhzssp.memorandum.feature.codex.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;
import org.zhzssp.memorandum.feature.codex.verify.CheckpointService;

/**
 * Codex 页面视图（Thymeleaf）。
 *
 * <p>只负责渲染外壳；数据一律由页面内 fetch 调 {@code /api/codex/**} 获取。
 * 这样做的原因是索引与受限执行都是耗时操作，服务端渲染会让页面在执行期间白屏。</p>
 */
@Controller
public class CodexViewController {

    private final RepoRegistryService registry;
    private final CheckpointService checkpointService;
    private final org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard writeGuard;
    private final org.zhzssp.memorandum.feature.codex.gap.GapService gapService;
    private final org.zhzssp.memorandum.feature.codex.distill.DistillService distillService;
    private final org.zhzssp.memorandum.feature.codex.distill.ExamService examService;

    public CodexViewController(RepoRegistryService registry,
                               CheckpointService checkpointService,
                               org.zhzssp.memorandum.feature.codex.sediment.DocWriteGuard writeGuard,
                               org.zhzssp.memorandum.feature.codex.gap.GapService gapService,
                               org.zhzssp.memorandum.feature.codex.distill.DistillService distillService,
                               org.zhzssp.memorandum.feature.codex.distill.ExamService examService) {
        this.registry = registry;
        this.checkpointService = checkpointService;
        this.writeGuard = writeGuard;
        this.gapService = gapService;
        this.distillService = distillService;
        this.examService = examService;
    }

    @GetMapping("/codex")
    public String dashboard(@AuthenticationPrincipal UserDetails principal, Model model) {
        model.addAttribute("codexEnabled", registry.enabled());
        model.addAttribute("codexOperational", registry.operational());
        model.addAttribute("gitVersion", registry.gitVersion());
        model.addAttribute("verifyEnabled", checkpointService.enabled());
        return "codex";
    }

    /** 知识落地检验面板（P1）。 */
    @GetMapping("/codex/checkpoints")
    public String checkpoints(@AuthenticationPrincipal UserDetails principal, Model model) {
        model.addAttribute("verifyEnabled", checkpointService.enabled());
        model.addAttribute("requirePrediction", checkpointService.requirePrediction());
        return "checkpoint";
    }

    /** 知识策展面板（P2）：CI 报告 + 沉淀 + 分支审阅。 */
    @GetMapping("/codex/curate")
    public String curate(@AuthenticationPrincipal UserDetails principal, Model model) {
        model.addAttribute("codexEnabled", registry.enabled());
        // 写入开关单独回显：CI 只读可用而沉淀不可用是完全正常的状态，
        // 不解释清楚用户会以为整个页面坏了
        model.addAttribute("writeEnabled", writeGuard.enabled());
        return "curate";
    }

    /** 知识缺口看板（P3）：三源合流 + 止损线召回。 */
    @GetMapping("/codex/gaps")
    public String gaps(@AuthenticationPrincipal UserDetails principal, Model model) {
        model.addAttribute("codexEnabled", registry.enabled());
        model.addAttribute("gapEnabled", gapService.enabled());
        return "gap";
    }

    /**
     * 蒸馏与定线（P4）：原料→Guide 草稿、Guide→检验题、以及「我现在该干什么」。
     *
     * <p>三个开关分别回显：起草可用而落盘不可用是刻意的中间状态
     * （先看产物质量，再给写权限），不解释清楚用户会以为页面坏了。</p>
     */
    @GetMapping("/codex/distill")
    public String distill(@AuthenticationPrincipal UserDetails principal, Model model) {
        model.addAttribute("codexEnabled", registry.enabled());
        model.addAttribute("distillEnabled", distillService.enabled());
        model.addAttribute("examEnabled", examService.enabled());
        model.addAttribute("writeEnabled", writeGuard.enabled());
        return "distill";
    }
}
