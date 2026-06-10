package org.zhzssp.memorandum.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.zhzssp.memorandum.service.CustomUserDetailsService;

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
                // 保持CSRF保护，但为API/WS端点配置豁免
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(
                                "/user-logged-in",
                                "/due-dates",
                                "/ws/agent/**",
                                "/api/agent/**"
                        )
                )
                .authorizeHttpRequests(auth -> auth
                        // 允许访问根路径与登录、注册及静态资源
                        .requestMatchers("/", "/register", "/login", "/css/**", "/js/**",
                                "/agent/**", "/user-logged-in").permitAll()
                        // /ws/agent/** 需要登录 -> 由 JSESSIONID 携带 Principal
                        .anyRequest().authenticated()
                )
                .formLogin(login -> login
                        .loginPage("/login")
                        .defaultSuccessUrl("/select-features", true)
                        .permitAll()
                )
                .logout(logout -> logout.permitAll());
        return http.build();
    }
}
