package com.ysulab.yapper.hub;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * The board inside the Yapper app is loaded from the app itself (WebViewAssetLoader's origin), so its
 * API calls are cross-origin. Only that origin (HUB_CORS_ORIGINS) may call the API from a page. The CORS
 * filter runs before {@link TokenFilter}, so preflights pass and 401s still carry CORS headers.
 */
@Configuration
public class CorsConfig {

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(HubProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        List<String> origins = Arrays.stream(props.getCorsOrigins().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        c.setAllowedOrigins(origins);
        c.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        c.setMaxAge(86_400L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", c);
        FilterRegistrationBean<CorsFilter> reg = new FilterRegistrationBean<>(new CorsFilter(src));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}
