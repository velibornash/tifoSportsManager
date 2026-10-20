package org.tifo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // All static resources are now served from the default classpath:/static/ location.
        //
        // The cache policy lives here because it bit us twice in one afternoon (owner, 2026-10-19):
        // the browser kept serving an **old** copy of a script, so a fix that was on the server never
        // reached the screen - the rename threw "load is not defined" from a line that no longer
        // existed, and a restyled card stayed the old colour. Nothing was wrong with the code; the
        // browser was simply never asked.
        //
        // So: the scripts and the stylesheets are cached, but only for a minute, and every response
        // carries a validator, so a reload re-checks them and picks up whatever changed. The images
        // keep a longer life - they are large and they do not change under the manager's feet.
        registry.addResourceHandler("/js/**")
                .addResourceLocations("classpath:/static/js/")
                .setCacheControl(org.springframework.http.CacheControl.maxAge(60, TimeUnit.SECONDS)
                        .cachePublic()
                        .mustRevalidate());

        registry.addResourceHandler("/css/**")
                .addResourceLocations("classpath:/static/css/")
                .setCacheControl(org.springframework.http.CacheControl.maxAge(60, TimeUnit.SECONDS)
                        .cachePublic()
                        .mustRevalidate());

        registry.addResourceHandler("/images/**")
                .addResourceLocations("classpath:/static/images/")
                .setCacheControl(org.springframework.http.CacheControl.maxAge(1, TimeUnit.HOURS)
                        .cachePublic());

        // The pages themselves are never cached. They are small, and a page that is a minute old
        // can point at a script or a stylesheet that has since been replaced - which is how a fix
        // lands on the server and never reaches the screen.
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(org.springframework.http.CacheControl.noCache());
    }
}
