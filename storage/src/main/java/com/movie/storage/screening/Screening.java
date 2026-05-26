package com.movie.storage.screening;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "screenings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Screening {

    @Id
    @Column(length = 128)
    private String id;

    @Column(name = "movie_title", nullable = false, length = 255)
    private String movieTitle;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "total_ticket_count", nullable = false)
    private int totalTicketCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Screening(String id, String movieTitle, Instant startsAt, int totalTicketCount) {
        if (totalTicketCount <= 0) {
            throw new IllegalArgumentException("totalTicketCount must be greater than zero");
        }
        this.id = id;
        this.movieTitle = movieTitle;
        this.startsAt = startsAt;
        this.totalTicketCount = totalTicketCount;
        this.createdAt = Instant.now();
    }
}
