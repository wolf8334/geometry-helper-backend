package com.xhr.geometryhelperbackend;

import com.xhr.geometryhelperbackend.config.FollowupProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FollowupProperties.class)
public class GeometryHelperBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeometryHelperBackendApplication.class, args);
    }

}
