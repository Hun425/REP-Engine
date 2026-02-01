package com.rep.simulator.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * CORS 설정
 *
 * 프론트엔드에서 API 접근을 허용합니다.
 */
@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(
                "http://localhost:5173",  // Vite 기본 포트
                "http://localhost:3001",  // 대체 포트
                "http://localhost:3000",  // 대체 포트
                "http://frontend:80"      // Docker
            )
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600)
    }
}
