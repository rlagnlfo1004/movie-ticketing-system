package com.movie.storage.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import com.movie.storage.screening.Screening;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "tickets",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tickets_screening_queue_token", columnNames = {"screening_id", "queue_token"}),
                @UniqueConstraint(name = "uk_tickets_screening_idempotency_key", columnNames = {"screening_id", "idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_tickets_screening_issued_at", columnList = "screening_id, issued_at DESC")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketIssuance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screening_id", nullable = false, length = 128)
    private String screeningId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "screening_id",
            referencedColumnName = "id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_tickets_screening")
    )
    private Screening screening;

    @Column(name = "queue_token", nullable = false, length = 512)
    private String queueToken;

    @Column(name = "idempotency_key", nullable = false, length = 256)
    private String idempotencyKey;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TicketIssuanceStatus status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    public TicketIssuance(String screeningId, String queueToken, String idempotencyKey, int quantity) {
        if (quantity != 1) {
            throw new IllegalArgumentException("quantity must be exactly 1");
        }
        this.screeningId = screeningId;
        this.queueToken = queueToken;
        this.idempotencyKey = idempotencyKey;
        this.quantity = quantity;
        this.status = TicketIssuanceStatus.SUCCEEDED;
        this.issuedAt = Instant.now();
    }
}
