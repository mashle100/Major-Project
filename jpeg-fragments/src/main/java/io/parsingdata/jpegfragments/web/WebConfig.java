package io.parsingdata.jpegfragments.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve fragmented images
        registry.addResourceHandler("/fragmented/**")
                .addResourceLocations("file:" + System.getProperty("user.dir") + "/fragmented/");

        // Serve reconstructed images
        registry.addResourceHandler("/reconstructed/**")
                .addResourceLocations("file:" + System.getProperty("user.dir") + "/reconstructed_images/");

        // Serve uploaded images
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + System.getProperty("user.dir") + "/uploads/");
    }
}
