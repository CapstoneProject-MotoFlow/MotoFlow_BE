package fu.hl.g64.motorflow.account.repository;

import fu.hl.g64.motorflow.account.entity.enums.AccountStatus;
import fu.hl.g64.motorflow.account.entity.enums.Role;
import fu.hl.g64.motorflow.account.entity.UserAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /**
     * Row-locked so that parallel wrong passwords cannot slip past the failed-attempt counter.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.username = :username")
    Optional<UserAccount> findByUsernameForUpdate(String username);

    boolean existsByRole(Role role);

    boolean existsByUsername(String username);

    List<UserAccount> findByRoleAndStatus(Role role, AccountStatus status);

    List<UserAccount> findByRoleAndStoreIdAndStatus(Role role, Long storeId, AccountStatus status);

    long countByStoreIdAndStatus(Long storeId, AccountStatus status);
}
