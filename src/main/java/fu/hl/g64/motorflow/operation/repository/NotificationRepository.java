package fu.hl.g64.motorflow.operation.repository;

import fu.hl.g64.motorflow.operation.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    long countByRecipientIdAndIsReadFalse(Long recipientId);

    List<Notification> findTop5ByRecipientIdAndIsReadFalseOrderByCreatedAtDescIdDesc(Long recipientId);
}
