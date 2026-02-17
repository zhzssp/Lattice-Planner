package org.zhzssp.memorandum.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

import org.springframework.security.core.context.SecurityContextHolder;
import org.zhzssp.memorandum.service.CustomUserDetailsService;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@EnableWebSecurity
@Configuration
public class WebSecurityConfig {

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 保持CSRF保护，但为API端点配置豁免
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers("/user-logged-in", "/due-dates")
                )
                .authorizeHttpRequests(auth -> auth
                        // 允许访问根路径与登录、注册及静态资源
                        .requestMatchers("/", "/register", "/login", "/css/**", "/js/**", "/user-logged-in").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(login -> login
                        .loginPage("/login")
                        .defaultSuccessUrl("/select-features", true) // 控制登录成功后跳转到dashboard, 同时设置登录状态为true
                        .permitAll()
                )
                .logout(logout -> logout.permitAll());
        return http.build();
    }
}

