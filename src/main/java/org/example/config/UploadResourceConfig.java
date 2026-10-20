package org.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Serves manager-uploaded images from the filesystem (owner, 2026-09-28).
 *
 * <p>This exists because of a trap that is easy to walk into. Everything under
 * {@code src/main/resources/static} is served from the <b>classpath</b>, and the classpath copy is
 * made at build time — so an image written into that source directory at runtime is not served until
 * the next rebuild, and in a packaged jar the directory is not writable at all. An upload feature
 * that saves there appears to work and then 404s every file it just accepted.
 *
 * <p>So uploads go to a real directory outside the classpath, configured by
 * {@code app.uploads.dir} and defaulted to {@code ./uploads} beside the running application.
 */
@Configuration
public class UploadResourceConfig implements WebMvcConfigurer {

    private final Path uploadRoot;

    public UploadResourceConfig(@Value("${app.uploads.dir:./uploads}") String uploadDir) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    public Path getUploadRoot() {
        return uploadRoot;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadRoot.toUri().toString());
    }
}
