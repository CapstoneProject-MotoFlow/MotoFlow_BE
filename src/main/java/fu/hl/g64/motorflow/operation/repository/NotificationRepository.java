package fu.hl.g64.motorflow.operation.repository;

import fu.hl.g64.motorflow.operation.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    long countByRecipientIdAndIsReadFalse(Long recipientId);

    Page<Notification> findByRecipientId(Long recipientId, Pageable pageable);

    Page<Notification> findByRecipientIdAndIsReadFalse(Long recipientId, Pageable pageable);

    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

    /** UC-87 "mark all as read". */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.isRead = true, n.readAt = :now where n.recipientId = :recipientId and n.isRead = false")
    int markAllRead(Long recipientId, LocalDateTime now);

    List<Notification> findTop5ByRecipientIdAndIsReadFalseOrderByCreatedAtDescIdDesc(Long recipientId);
}
