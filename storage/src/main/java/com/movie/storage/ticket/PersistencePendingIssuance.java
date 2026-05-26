package com.movie.storage.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "persistence_pending_issuance",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pending_issuance_screening_queue_token", columnNames = {"screening_id", "queue_token"}),
                @UniqueConstraint(name = "uk_pending_issuance_screening_idempotency_key", columnNames = {"screening_id", "idempotency_key"})
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PersistencePendingIssuance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screening_id", nullable = false, length = 128)
    private String screeningId;

    @Column(name = "queue_token", nullable = false, length = 512)
    private String queueToken;

    @Column(name = "idempotency_key", nullable = false, length = 256)
    private String idempotencyKey;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "inventory_consumed", nullable = false)
    private boolean inventoryConsumed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PersistencePendingStatus status;

    @Column(name = "failure_reason", nullable = false, length = 2048)
    private String failureReason;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PersistencePendingIssuance(String screeningId, String queueToken, String idempotencyKey, int quantity, String failureReason) {
        this.screeningId = screeningId;
        this.queueToken = queueToken;
        this.idempotencyKey = idempotencyKey;
        this.quantity = quantity;
        this.inventoryConsumed = true;
        this.status = PersistencePendingStatus.PENDING;
        this.failureReason = failureReason;
        this.attemptCount = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }
}
