package org.zhzssp.memorandum.feature.agent.mcp.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.feature.agent.mcp.McpAuthService;
import org.zhzssp.memorandum.feature.agent.mcp.entity.McpToken;
import org.zhzssp.memorandum.feature.agent.mcp.repository.McpTokenRepository;
import org.zhzssp.memorandum.repository.UserRepository;

import java.util.List;

/**
 * MCP 设置页面控制器（S3 管理 UI）。
 */
@Controller
@RequestMapping("/mcp")
public class McpSettingsController {

    @Autowired
    private McpTokenRepository tokenRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private McpAuthService authService;

    @GetMapping("/settings")
    public String settingsPage(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        User user = userRepo.findByUsername(userDetails.getUsername()).orElseThrow();
        List<McpToken> tokens = tokenRepo.findByUserIdOrderByCreatedAtDesc(user.getId());
        model.addAttribute("tokens", tokens);
        model.addAttribute("username", user.getUsername());
        return "mcp-settings";
    }
}
