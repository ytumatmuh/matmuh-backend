package com.matmuh.matmuhsite.core.config;

import com.matmuh.matmuhsite.business.abstracts.UserService;
import com.matmuh.matmuhsite.core.exceptionHandlers.CustomAccessDeniedHandler;
import com.matmuh.matmuhsite.core.exceptionHandlers.CustomAuthenticationEntryPointHandler;
import com.matmuh.matmuhsite.core.properties.StudentPortalProperties;
import com.matmuh.matmuhsite.core.security.JwtAuthFilter;
import com.matmuh.matmuhsite.core.security.ServiceKeyAuthFilter;
import com.matmuh.matmuhsite.core.security.oauth2.OAuth2LoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.CacheControlHeadersWriter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ServiceKeyAuthFilter serviceKeyAuthFilter;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final CustomAccessDeniedHandler customAccessDeniedHandler;
    private final CustomAuthenticationEntryPointHandler customAuthenticationEntryPointHandler;
    private final OAuth2AuthorizationRequestResolver authorizationRequestResolver;
    private final StudentPortalProperties studentPortal;

    public SecurityConfig(
            JwtAuthFilter jwtAuthFilter,
            ServiceKeyAuthFilter serviceKeyAuthFilter,
            UserService userService,
            PasswordEncoder passwordEncoder,
            OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
            CustomAccessDeniedHandler customAccessDeniedHandler,
            CustomAuthenticationEntryPointHandler customAuthenticationEntryPointHandler,
            OAuth2AuthorizationRequestResolver authorizationRequestResolver,
            StudentPortalProperties studentPortal) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.serviceKeyAuthFilter = serviceKeyAuthFilter;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
        this.customAccessDeniedHandler = customAccessDeniedHandler;
        this.customAuthenticationEntryPointHandler = customAuthenticationEntryPointHandler;
        this.authorizationRequestResolver = authorizationRequestResolver;
        this.studentPortal = studentPortal;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
        return httpSecurity

                .cors(Customizer.withDefaults())

                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(this::authorizeRequests)
                .headers(x -> x
                        .cacheControl(cache -> cache.disable())
                        .addHeaderWriter((request, response) -> {
                            if (!request.getRequestURI().startsWith("/cdn/")) {
                                new CacheControlHeadersWriter().writeHeaders(request, response);
                            }
                        })
                )
                .exceptionHandling(x -> x
                        .accessDeniedHandler(customAccessDeniedHandler)
                        .authenticationEntryPoint(customAuthenticationEntryPointHandler)
                )
                .sessionManagement(x -> x
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation().migrateSession()
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(serviceKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(jwtAuthFilter, ServiceKeyAuthFilter.class)
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(endpoint -> endpoint
                                .baseUri("/api/oauth2/microsoft")
                                .authorizationRequestResolver(authorizationRequestResolver)
                        )
                        .redirectionEndpoint(redirection -> redirection
                                .baseUri("/api/login/oauth2/code/*")
                        )
                        .successHandler(oAuth2LoginSuccessHandler)
                )
                .build();
    }

    private void authorizeRequests(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry x) {
        x
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Hata sayfası asıl isteğin yetkisiyle zaten süzüldü (ERROR dispatch'te yol /error);
                // kapalı kalırsa her konteyner hatası (firewall reddi vb.) gerçek durumu yerine 401 döner.
                .requestMatchers("/error").permitAll()
                .requestMatchers(
                        "/api/oauth2/**",
                        "/api/login/**",
                        "/api/login/oauth2/code/**"
                ).permitAll()
                .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll();

        studentSurface(x);

        x
                .requestMatchers(HttpMethod.GET, "/api/uploads/images/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/uploads/public/**").permitAll()
                .requestMatchers("/api/swagger-ui.html", "/api/swagger-ui/**", "/api/v3/api-docs/**").permitAll()
                // YAML kopyası eskiden yalnız fallback'le (authenticated) açıktı; anonime açmak genişletme
                // olurdu, aynı belge JSON olarak zaten herkese açık.
                .requestMatchers(HttpMethod.GET, "/api/v3/api-docs.yaml").hasRole("ADMIN")
                .requestMatchers("/cdn/**").permitAll()

                .requestMatchers("/api/service-keys/**").hasRole("ADMIN")

                .requestMatchers("/api/calendar-admin/**").hasAnyRole("ADMIN", "EDITOR", "CONTENT_WRITE")

                .requestMatchers(HttpMethod.GET, "/api/calendar", "/api/calendar/weekly").permitAll()

                .requestMatchers(HttpMethod.GET, "/api/cms/data").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/cms/collections/me").hasAnyRole("ADMIN", "EDITOR")
                .requestMatchers(HttpMethod.GET, "/api/cms/collections/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/cms/content", "/api/cms/content/all",
                        "/api/cms/public/*/content", "/api/cms/public/*/content/all",
                        "/api/cms/public/*/data").permitAll()
                // Sync'i insan token'ı çalıştıramaz: yetenek yalnız servis anahtarlarında var.
                // Bir geliştiricinin lokalinden bayat manifest'le prod'u uzlaştırması
                // bu yüzden yapısal olarak kapalı.
                .requestMatchers(HttpMethod.POST, "/api/cms/sync").hasRole("SCHEMA_SYNC")
                .requestMatchers(HttpMethod.PUT, "/api/cms/content", "/api/cms/draft").hasAnyRole("ADMIN", "EDITOR")
                .requestMatchers(HttpMethod.DELETE, "/api/cms/draft").hasAnyRole("ADMIN", "EDITOR")
                // CONTENT_WRITE: Egehan'ın botu için makine anahtarı — koleksiyon yazma, medya,
                // toplu açılış aktarımı ve dört sağlayıcı koleksiyonun REST silmesi. Katalog
                // POST/PATCH REST'te ADMIN kaldı; bot yazmaları CMS API'sinden yapar.
                .requestMatchers(HttpMethod.POST, "/api/cms/media").hasAnyRole("ADMIN", "EDITOR", "CONTENT_WRITE")
                .requestMatchers(HttpMethod.POST, "/api/cms/collections/**").hasAnyRole("ADMIN", "EDITOR", "CONTENT_WRITE")
                .requestMatchers(HttpMethod.PUT, "/api/cms/collections/**").hasAnyRole("ADMIN", "EDITOR", "CONTENT_WRITE")
                .requestMatchers(HttpMethod.DELETE, "/api/cms/collections/**").hasAnyRole("ADMIN", "EDITOR", "CONTENT_WRITE")

                .requestMatchers(HttpMethod.POST, "/api/lectures/{id}/offerings").hasAnyRole("ADMIN", "EDITOR")
                .requestMatchers(HttpMethod.GET,  "/api/lectures/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/lectures/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/lectures/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/lectures/**").hasAnyRole("ADMIN", "CONTENT_WRITE")

                .requestMatchers(HttpMethod.GET,    "/api/search").permitAll()

                .requestMatchers(HttpMethod.GET,    "/api/elective-groups/**").permitAll()
                .requestMatchers(HttpMethod.POST,   "/api/elective-groups/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH,  "/api/elective-groups/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/elective-groups/**").hasAnyRole("ADMIN", "CONTENT_WRITE")

                .requestMatchers("/api/lecture-notes/**").hasRole("ADMIN")

                .requestMatchers(HttpMethod.POST, "/api/lecture-offerings/import").hasAnyRole("ADMIN", "EDITOR", "CONTENT_WRITE")
                .requestMatchers(HttpMethod.POST, "/api/lecture-offerings/**").hasAnyRole("ADMIN", "EDITOR")
                .requestMatchers(HttpMethod.PUT, "/api/lecture-offerings/**").hasAnyRole("ADMIN", "EDITOR")
                .requestMatchers(HttpMethod.PATCH, "/api/lecture-offerings/**").hasAnyRole("ADMIN", "EDITOR")
                .requestMatchers(HttpMethod.DELETE, "/api/lecture-offerings/**").hasAnyRole("ADMIN", "CONTENT_WRITE")

                .requestMatchers(HttpMethod.GET, "/api/academic-years").permitAll()

                .requestMatchers(HttpMethod.GET, "/api/users/me").authenticated()
                .requestMatchers("/api/users/**").hasRole("ADMIN")

                .requestMatchers(HttpMethod.GET,  "/api/staff/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/staff").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/staff/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/staff/**").hasAnyRole("ADMIN", "CONTENT_WRITE")

                // Kuralı olmayan uç kimseye açık değil; yeni uç buraya yazılmadan çalışmaz
                // (SecurityEndpointCoverageTest ADMIN için her ucu sorar).
                .anyRequest().denyAll();
    }

    // Genel kurallardan önce kaydedilmeli: aynı yolların geniş eşleşmeleri (GET /api/lectures/**,
    // GET /api/staff/**) sonradan permitAll.
    private void studentSurface(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry x) {
        if (studentPortal.isEnabled()) {
            x
                    .requestMatchers(HttpMethod.GET, "/api/uploads/files/**").authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/calendar/me").authenticated()
                    .requestMatchers("/api/enrollments/**").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/lectures/{id}/notes").hasAnyRole("ADMIN", "USER")
                    .requestMatchers(HttpMethod.GET,  "/api/lectures/{id}/notes").authenticated()
                    .requestMatchers(HttpMethod.GET,  "/api/lectures/{id}/offerings").authenticated()
                    .requestMatchers(HttpMethod.GET,  "/api/lectures/{id}/statistics").authenticated()
                    // Sahiplik satır bazlı: kararı LectureNoteManager verir.
                    .requestMatchers(HttpMethod.GET, "/api/lecture-notes/me").authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/lecture-notes/{id}").authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/lecture-notes/{id}").authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/lecture-offerings/**").authenticated()
                    .requestMatchers(HttpMethod.GET,  "/api/staff/{id}/notes").authenticated()
                    .requestMatchers(HttpMethod.GET,  "/api/staff/{id}/offerings").authenticated();
            return;
        }

        // Özellik geri gelebilir: ADMIN deneyebilsin diye denyAll değil. Dönem verisinin bakımını
        // EDITOR yaptığı için o okumalar ona da açık.
        x
                .requestMatchers(HttpMethod.GET, "/api/uploads/files/**").hasAnyRole("ADMIN", "EDITOR")
                .requestMatchers(HttpMethod.GET, "/api/calendar/me").hasRole("ADMIN")
                .requestMatchers("/api/enrollments/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/lectures/{id}/notes").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET,  "/api/lectures/{id}/notes").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET,  "/api/lectures/{id}/offerings").hasAnyRole("ADMIN", "EDITOR")
                .requestMatchers(HttpMethod.GET,  "/api/lectures/{id}/statistics").hasAnyRole("ADMIN", "EDITOR")
                .requestMatchers(HttpMethod.GET, "/api/lecture-notes/me").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/lecture-notes/{id}").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/lecture-notes/{id}").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/lecture-offerings/**").hasAnyRole("ADMIN", "EDITOR")
                .requestMatchers(HttpMethod.GET,  "/api/staff/{id}/notes").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET,  "/api/staff/{id}/offerings").hasAnyRole("ADMIN", "EDITOR");
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}