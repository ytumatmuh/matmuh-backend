package com.matmuh.matmuhsite.business.concretes;

import com.matmuh.matmuhsite.business.abstracts.SecurityService;
import com.matmuh.matmuhsite.business.constants.UserMessages;
import com.matmuh.matmuhsite.core.exceptions.ResourceNotFoundException;
import com.matmuh.matmuhsite.core.helpers.LogMasks;
import com.matmuh.matmuhsite.dataAccess.abstracts.UserDao;
import com.matmuh.matmuhsite.entities.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class SecurityManager implements SecurityService {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String SERVICE_KEY_PRINCIPAL = "service:";

    private final UserDao userDao;
    private final JdbcTemplate jdbcTemplate;

    private final Logger logger = LoggerFactory.getLogger(SecurityManager.class);

    public SecurityManager(UserDao userDao, JdbcTemplate jdbcTemplate) {
        this.userDao = userDao;
        this.jdbcTemplate = jdbcTemplate;
    }


    @Override
    public User getAuthenticatedUserFromContext() {
        logger.debug("Getting authenticated user");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            logger.warn("User not authenticated or anonymous");
            throw new ResourceNotFoundException(UserMessages.USER_NOT_AUTHENTICATED);
        }

        if (authentication.getPrincipal() instanceof User user) {
            return user;
        }

        // Servis anahtarı bir kullanıcı değil; e-posta ile aramak boşa sorgu, üstelik denetim
        // alanı flush sırasında çözüldüğü için o sorgu iç içe flush'a yol açıyordu.
        if (authentication.getName().startsWith(SERVICE_KEY_PRINCIPAL)) {
            throw new ResourceNotFoundException(UserMessages.USER_NOT_AUTHENTICATED);
        }

        return userDao.findByEmail(authentication.getName()).orElseThrow(() -> {
            logger.warn("Authenticated principal could not be resolved to a user: {}", LogMasks.principal(authentication.getName()));
            return new ResourceNotFoundException(UserMessages.USER_NOT_AUTHENTICATED);
        });
    }

    @Override
    public User getAuthenticatedUserFromDatabase() {
        User userFromContext = getAuthenticatedUserFromContext();
        UUID userId = userFromContext.getId();

        return userDao.findById(userId).orElseThrow(() -> new ResourceNotFoundException(UserMessages.USER_NOT_FOUND));
    }

    @Override
    // Denetim alanı güncellemede flush sırasında (@PreUpdate) doldurulur. Burada JPA sorgusu
    // atmak iç içe flush tetikler ve Hibernate "Found shared references to a collection" ile
    // güncellemeyi 500'e düşürür (bot ders/personel güncelleyemiyordu, 23 Eylül). Varlık JDBC ile
    // sorulur — Hibernate flush'ına girmez — ve satır sorgusuz proxy olarak döner.
    public User getSystemUser() {
        var exists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE id = ? AND NOT is_deleted", Integer.class, SYSTEM_USER_ID);
        if (exists == null || exists == 0) {
            throw new ResourceNotFoundException(UserMessages.SYSTEM_USER_NOT_FOUND);
        }
        return userDao.getReferenceById(SYSTEM_USER_ID);
    }
}
