package org.zhzssp.memorandum.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.entity.UserPreference;
import org.zhzssp.memorandum.repository.UserRepository;
import org.zhzssp.memorandum.service.UserPreferenceService;

import java.security.Principal;

@Controller
public class FeatureSelectionController {

    private static final String SESSION_KEY = "mindsetMode"; // 思维模式：execute/learn/plan

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPreferenceService userPreferenceService;

    @GetMapping("/select-features")
    public String selectFeaturesPage(jakarta.servlet.http.HttpSession session, Principal principal, Model model) {
        // 若已经选择过思维模式则直接跳转至 dashboard，防止回退再访问
        if (session.getAttribute(SESSION_KEY) != null) {
            return "redirect:/dashboard";
        }
        if (principal != null) {
            User user = userRepository.findByUsername(principal.getName()).orElse(null);
            if (user != null) {
                UserPreference pref = userPreferenceService.getOrCreatePreference(user);
                model.addAttribute("defaultMindsetMode", pref.getDefaultMindsetMode());
            }
        }
        return "selectFeatures";
    }

    @PostMapping("/select-features")
    public String saveMindsetMode(@RequestParam(name = "mindsetMode", required = false) String mode,
                                  HttpSession session) {
        // default to execute if nothing sent
        String validMode = mode != null && (mode.equals("execute") || mode.equals("learn") || mode.equals("plan"))
                ? mode : "execute";
        session.setAttribute(SESSION_KEY, validMode);
        return "redirect:/dashboard";
    }

    public static String getMindsetMode(HttpSession session) {
        Object obj = session.getAttribute(SESSION_KEY);
        return obj instanceof String ? (String) obj : "execute";
    }

    /**
     * 供 Dashboard 顶部的「切换视图模式」按钮使用：
     * 清除当前会话中的思维模式，下次进入重新选择。
     */
    @GetMapping("/change-mode")
    public String changeMode(HttpSession session) {
        session.removeAttribute(SESSION_KEY);
        return "redirect:/select-features";
    }
}
