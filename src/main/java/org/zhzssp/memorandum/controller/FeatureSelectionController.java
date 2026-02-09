package org.zhzssp.memorandum.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class FeatureSelectionController {

    private static final String SESSION_KEY = "selectedFeature"; // store single string value

    @GetMapping("/select-features")
    public String selectFeaturesPage(jakarta.servlet.http.HttpSession session) {
        // 若已经选择过功能模式则直接跳转至 dashboard，防止回退再访问
        if (session.getAttribute(SESSION_KEY) != null) {
            return "redirect:/dashboard";
        }
        return "selectFeatures";
    }

    @PostMapping("/select-features")
    public String saveSelectedFeature(@RequestParam(name = "selectedFeature", required = false) String feature,
                                      HttpSession session) {
        // default to tasks if nothing sent
        session.setAttribute(SESSION_KEY, feature != null ? feature : "tasks");
        return "redirect:/dashboard";
    }

    public static String getSelectedFeature(HttpSession session) {
        Object obj = session.getAttribute(SESSION_KEY);
        return obj instanceof String ? (String) obj : "tasks";
    }

    /**
     * 供 Dashboard 顶部的「切换视图模式」按钮使用：
     * 清除当前会话中的视图选择，下次进入重新选择任务/笔记视图。
     */
    @GetMapping("/change-mode")
    public String changeMode(HttpSession session) {
        session.removeAttribute(SESSION_KEY);
        return "redirect:/select-features";
    }
}
