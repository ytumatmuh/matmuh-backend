package com.matmuh.matmuhsite.core.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// Sunucuya SSH olmadan boş veritabanında ilk ADMIN'i açmanın tek yolu; rol ucu ADMIN veremez.
@Configuration
@ConfigurationProperties(prefix = "bootstrap.admin")
@Getter
@Setter
public class BootstrapAdminProperties {

    private String email;

    private String password;

    private String firstName;

    private String lastName;
}
