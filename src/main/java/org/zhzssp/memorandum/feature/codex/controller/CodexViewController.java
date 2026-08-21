package org.zhzssp.memorandum.feature.codex.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.zhzssp.memorandum.feature.codex.service.RepoRegistryService;

/**
 * Codex 页面视图（Thymeleaf）。
 *
 * <p>只负责渲染外壳；数据一律由页面内 fetch 调 {@code /api/codex/**} 获取。
 * 这样做的原因是索引与同步是耗时操作，服务端渲染会让页面在索引期间白屏。</p>
 */
@Controller
public class CodexViewController {

    private final RepoRegistryService registry;

    public CodexViewController(RepoRegistryService registry) {
        this.registry = registry;
    }

    @GetMapping("/codex")
    public String dashboard(@AuthenticationPrincipal UserDetails principal, Model model) {
        model.addAttribute("codexEnabled", registry.enabled());
        model.addAttribute("codexOperational", registry.operational());
        model.addAttribute("gitVersion", registry.gitVersion());
        return "codex";
    }
}
