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

    @GetMapping("/settings")
    public String settingsPage(Principal principal, Model model) {
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        UserPreference pref = preferenceService.getOrCreatePreference(user);
        model.addAttribute("preference", pref);
        return "preferenceSettings";
    }

    @PostMapping("/update")
    public String updatePreference(@RequestParam(required = false) Integer maxVisibleTasks,
                                   @RequestParam(required = false) Boolean showFutureTasks,
                                   @RequestParam(required = false) Boolean showStatistics,
                                   Principal principal) {
        User user = userRepository.findByUsername(principal.getName()).orElseThrow();
        UserPreference pref = preferenceService.getOrCreatePreference(user);

        if (maxVisibleTasks != null && maxVisibleTasks > 0) {
            pref.setMaxVisibleTasks(maxVisibleTasks);
        } else {
            pref.setMaxVisibleTasks(null);
        }

        if (showFutureTasks != null) {
            pref.setShowFutureTasks(showFutureTasks);
        }

        if (showStatistics != null) {
            pref.setShowStatistics(showStatistics);
        }

        preferenceService.savePreference(pref);
        return "redirect:/dashboard";
    }
}
