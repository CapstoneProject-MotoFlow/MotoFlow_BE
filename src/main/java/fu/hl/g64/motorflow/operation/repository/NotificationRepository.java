package fu.hl.g64.motorflow.operation.repository;

import fu.hl.g64.motorflow.operation.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
