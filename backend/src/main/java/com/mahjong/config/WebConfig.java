package com.mahjong.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "http://192.168.3.101",
                        "http://192.168.3.101:*",
                        "http://10.190.131.87",
                        "http://10.190.131.87:*",
                        "http://82.156.207.98",
                        "http://82.156.207.98:*")
                .allowedMethods("*");
    }
}
