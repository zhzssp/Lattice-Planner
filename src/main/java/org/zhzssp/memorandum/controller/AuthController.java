package org.zhzssp.memorandum.controller;

import org.zhzssp.memorandum.entity.User;
import org.zhzssp.memorandum.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;

// 没有使用ResponseBody，表示该类的方法返回的是视图名称（模板名称）
// 若使用ResponseBody / RestController，则表示返回的是封装在HTTP相应之中的JSON数据 --> 格式由converter决定
@Controller
public class AuthController {
    @Autowired
    private UserRepository userRepository;

    @GetMapping("/register")
    public String registerForm() {
        return "register"; // 渲染register.html模板
    }

    // 不使用"/"时，login/register或addMemo/register等情况会导致无法正确识别表单提交路径（硬编码的缘故）
    // 接收POST /register请求（内容包括user, password）,处理用户注册
    @PostMapping("/register")
    public String register(@RequestParam String username, @RequestParam String password, Model model) {
        // 后端验证密码长度
        if (password.length() < 5) {
            // 密码不符合要求，返回注册页面并显示错误消息
            model.addAttribute("errorMessage", "Password must be at least 5 characters long.");
            // 使用redirect会发出GET请求，导致错误信息无法显示
            return "register";
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(new BCryptPasswordEncoder().encode(password));
        // 调用JPA自动生成的SQL语句，保存用户到数据库
        userRepository.save(user);
        // 注册成功后重定向到login页面
        return "redirect:/login";
    }

    /**
     * 登录页：
     * - 默认保持原有逻辑：若已登录且已选择模式，则直接跳转 dashboard；
     * - 当带上 ?force=true 时，强制清理登录状态和会话，回到登录界面。
     */
    @GetMapping("/login")
    public String loginForm(
            @RequestParam(name = "force", required = false, defaultValue = "false") boolean force,
            jakarta.servlet.http.HttpSession session) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // 强制重新登录：清理认证与会话
        if (force) {
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
                SecurityContextHolder.clearContext();
            }
            if (session != null) {
                session.invalidate();
            }
            return "login";
        }

        // 原有逻辑：已登录且已选择模式 -> 跳 dashboard
        boolean loggedIn = auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName());
        boolean modeChosen = session != null && session.getAttribute("selectedFeature") != null;
        if (loggedIn && modeChosen) {
            return "redirect:/dashboard";
        }
        return "login";
    }

    /**
     * 访问根路径时统一跳转到登录页，并强制重新登录。
     * 这样在浏览器地址栏输入 http://localhost:8080 时：
     * - 不会出现 Whitelabel Error Page；
     * - 且用户会被要求重新输入账号密码。
     */
    @GetMapping("/")
    public String root() {
        return "redirect:/login?force=true";
    }

    @GetMapping("/user-logged-in")
    // 返回值为数据而非视图名称
    @ResponseBody
    public boolean sendUserLoggedInNotification() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            
            System.out.println("=== Authentication Debug Info ===");
            System.out.println("Authentication object: " + authentication);
            
            if (authentication == null) {
                System.out.println("Authentication is NULL");
                return false;
            }
            
            boolean isAuthenticated = authentication.isAuthenticated();
            String username = authentication.getName();
            String authType = authentication.getClass().getSimpleName();

            // 出现anonymousUser的情况?
            System.out.println("Authentication type: " + authType);
            System.out.println("Is authenticated: " + isAuthenticated);
            System.out.println("Username: " + username);
            System.out.println("Is anonymous: " + username.equals("anonymousUser"));
            System.out.println("Authorities: " + authentication.getAuthorities());
            System.out.println("=================================");

            return isAuthenticated && !username.equals("anonymousUser");
            // return isAuthenticated;
        } catch (Exception e) {
            System.out.println("Error in authentication check: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
