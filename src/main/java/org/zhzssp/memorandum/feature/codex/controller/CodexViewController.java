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

    public CodexViewController(RepoRegistryService registry,
                               CheckpointService checkpointService) {
        this.registry = registry;
        this.checkpointService = checkpointService;
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
}
