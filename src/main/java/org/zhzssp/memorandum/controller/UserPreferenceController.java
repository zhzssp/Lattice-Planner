package org.zhzssp.memorandum.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.entity.UserPreference;
import org.zhzssp.memorandum.repository.UserRepository;
import org.zhzssp.memorandum.service.UserPreferenceService;

import java.security.Principal;

@Controller
@RequestMapping("/preference")
public class UserPreferenceController {

    @Autowired
    private UserPreferenceService preferenceService;

    @Autowired
    private UserRepository userRepository;

    /** 可选：?mode=execute|learn|plan 用于默认选中的思维模式 tab */
    @GetMapping("/settings")
    public String settingsPage(Principal principal,
                               @RequestParam(name = "mode", required = false) String mode,
                               Model model) {
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        UserPreference pref = preferenceService.getOrCreatePreference(user);
        model.addAttribute("preference", pref);
        model.addAttribute("theme", pref.getTheme() != null ? pref.getTheme() : "light");
        if (mode != null && (mode.equals("execute") || mode.equals("learn") || mode.equals("plan"))) {
            model.addAttribute("selectedMode", mode);
        } else {
            model.addAttribute("selectedMode", "execute");
        }
        return "preferenceSettings";
    }

    @PostMapping("/update")
    public String updatePreference(@RequestParam(required = false) Integer maxVisibleTasks,
                                   @RequestParam(required = false) Boolean showFutureTasks,
                                   @RequestParam(required = false) Boolean showStatistics,
                                   @RequestParam(required = false) String defaultMindsetMode,
                                   @RequestParam(required = false) String theme,
                                   @RequestParam(required = false) String defaultTaskView,
                                   @RequestParam(required = false) Integer fuzzyTaskDaysThreshold,
                                   @RequestParam(required = false) String showGoals,
                                   @RequestParam(required = false) String showFuzzyHint,
                                   @RequestParam(required = false) String showArchivedSection,
                                   @RequestParam(required = false) String showScoreSection,
                                   Principal principal) {
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        UserPreference pref = preferenceService.getOrCreatePreference(user);

        if (maxVisibleTasks != null && maxVisibleTasks > 0) {
            pref.setMaxVisibleTasks(maxVisibleTasks);
        } else {
            pref.setMaxVisibleTasks(null);
        }

        // 未勾选的 checkbox 不会出现在 POST 中，为 null，必须显式视为 false
        pref.setShowFutureTasks(Boolean.TRUE.equals(showFutureTasks));
        pref.setShowStatistics(Boolean.TRUE.equals(showStatistics));

        if (defaultMindsetMode != null && !defaultMindsetMode.isBlank()) {
            try {
                pref.setDefaultMindsetMode(UserPreference.MindsetMode.valueOf(defaultMindsetMode));
            } catch (IllegalArgumentException e) {
                pref.setDefaultMindsetMode(null);
            }
        } else {
            pref.setDefaultMindsetMode(null);
        }

        if (theme != null && (theme.equals("light") || theme.equals("dark"))) {
            pref.setTheme(theme);
        } else {
            pref.setTheme(null);
        }

        if (defaultTaskView != null && (defaultTaskView.equals("slot") || defaultTaskView.equals("energy"))) {
            pref.setDefaultTaskView(defaultTaskView);
        } else {
            pref.setDefaultTaskView(null);
        }

        if (fuzzyTaskDaysThreshold != null && fuzzyTaskDaysThreshold >= 0) {
            pref.setFuzzyTaskDaysThreshold(fuzzyTaskDaysThreshold);
        } else {
            pref.setFuzzyTaskDaysThreshold(null);
        }

        // 同上：未勾选时参数不提交为 null，"true".equalsIgnoreCase(null) == false
        pref.setShowGoals(showGoals != null && "true".equalsIgnoreCase(showGoals));
        pref.setShowFuzzyHint(showFuzzyHint != null && "true".equalsIgnoreCase(showFuzzyHint));
        pref.setShowArchivedSection(showArchivedSection != null && "true".equalsIgnoreCase(showArchivedSection));
        pref.setShowScoreSection(showScoreSection != null && "true".equalsIgnoreCase(showScoreSection));

        preferenceService.savePreference(pref);
        return "redirect:/dashboard";
    }

}
