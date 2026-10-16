package fu.hl.g64.motorflow.account.service;

import fu.hl.g64.motorflow.account.entity.PermissionHistory;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import fu.hl.g64.motorflow.account.repository.PermissionHistoryRepository;
import fu.hl.g64.motorflow.account.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Accounts are created only by Admin (BR-01), so a fresh database needs one Admin to start with.
 * When no Admin exists and motoflow.bootstrap.admin.* is set, this creates one with a temporary
 * password that must be changed at the first sign-in.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapAdminInitializer implements ApplicationRunner {

    private final UserAccountRepository users;
    private final PermissionHistoryRepository permissionHistory;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Value("${motoflow.bootstrap.admin.username:}")
    private String username;

    @Value("${motoflow.bootstrap.admin.password:}")
    private String password;

    @Value("${motoflow.bootstrap.admin.full-name:System Administrator}")
    private String fullName;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password) || users.existsByRole(Role.ADMIN)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        UserAccount admin = users.save(UserAccount.create(
                UserAccount.normalizeUsername(username), passwordEncoder.encode(password), fullName, Role.ADMIN, null, null, now));
        permissionHistory.save(PermissionHistory.accountCreated(admin, admin.getId(), "Bootstrap admin", now));
        log.info("Created bootstrap Admin account '{}'; its temporary password must be changed at first sign-in",
                admin.getUsername());
    }
}
