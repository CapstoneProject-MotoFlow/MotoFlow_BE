package fu.hl.g64.motorflow.transfer.entity;

import fu.hl.g64.motorflow.transfer.entity.enums.ReplenishmentRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A store's request for more stock, coordinated from other warehouses; any shortage goes to Owner for purchasing (BR-54, BR-55). */
@Entity
@Table(name = "replenishment_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReplenishmentRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requesting_store_id", nullable = false)
    private Long requestingStoreId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReplenishmentRequestStatus status;

    @Column(length = 500)
    private String note;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    public static ReplenishmentRequest create(Long requestingStoreId, String note, Long createdBy, LocalDateTime now) {
        ReplenishmentRequest request = new ReplenishmentRequest();
        request.requestingStoreId = requestingStoreId;
        request.status = ReplenishmentRequestStatus.AWAITING_COORDINATION;
        request.note = note;
        request.createdBy = createdBy;
        request.createdAt = now;
        return request;
    }

    /**
     * PM 17/11: a draft only its creator sees, not a request until {@link #submit}. Its created_at is when it was last
     * saved.
     */
    public static ReplenishmentRequest createDraft(Long requestingStoreId, String note, Long createdBy,
                                                   LocalDateTime now) {
        ReplenishmentRequest request = create(requestingStoreId, note, createdBy, now);
        request.status = ReplenishmentRequestStatus.DRAFT;
        return request;
    }

    /** The creator rewrites the whole draft. */
    public void changeDraft(Long requestingStoreId, String note, LocalDateTime now) {
        requireDraft();
        this.requestingStoreId = requestingStoreId;
        this.note = note;
        createdAt = now;
    }

    /** The draft becomes a request awaiting coordination, created now (UC-68). */
    public void submit(LocalDateTime now) {
        requireDraft();
        status = ReplenishmentRequestStatus.AWAITING_COORDINATION;
        createdAt = now;
    }

    public boolean isDraft() {
        return status == ReplenishmentRequestStatus.DRAFT;
    }

    private void requireDraft() {
        if (!isDraft()) {
            throw new IllegalStateException("replenishment request " + id + " is not a draft");
        }
    }

    /** UC-71: the first transfer order or purchase hand-over starts the coordination. */
    public void startCoordination() {
        if (status == ReplenishmentRequestStatus.AWAITING_COORDINATION) {
            status = ReplenishmentRequestStatus.IN_PROGRESS;
        }
    }

    /** UC-77: every transfer order of the request is closed and each part was received or passed to purchasing. */
    public void close(LocalDateTime now) {
        status = ReplenishmentRequestStatus.CLOSED;
        closedAt = now;
    }
}
