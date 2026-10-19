package com.matmuh.matmuhsite.core.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// Aynı kod iki siteye çıkıyor: resmi bölüm sitesi öğrenci girişi olmadan (false), tam özellikli
// site öğrenci özellikleriyle (true). Kapalı varsayılan güvenli taraf.
@Configuration
@ConfigurationProperties(prefix = "features.student-portal")
@Getter
@Setter
public class StudentPortalProperties {

    private boolean enabled = false;
}
