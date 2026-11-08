CREATE TABLE IF NOT EXISTS screenings (
  id VARCHAR(128) PRIMARY KEY,
  movie_title VARCHAR(255) NOT NULL,
  starts_at DATETIME(6) NOT NULL,
  total_seat_count INT NOT NULL,
  remaining_seat_count INT NOT NULL,
  created_at DATETIME(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS seat_holds (
  hold_id VARCHAR(36) PRIMARY KEY,
  screening_id VARCHAR(128) NOT NULL,
  queue_token VARCHAR(256) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  active_queue_token VARCHAR(256) GENERATED ALWAYS AS (CASE WHEN status='HELD' THEN queue_token ELSE NULL END) STORED,
  held_until DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_holds_screening FOREIGN KEY (screening_id) REFERENCES screenings(id),
  INDEX ix_holds_expiry (status, held_until),
  INDEX ix_holds_token (screening_id, queue_token),
  UNIQUE KEY uq_active_hold (screening_id, active_queue_token)
);

CREATE TABLE IF NOT EXISTS screening_seats (
  screening_id VARCHAR(128) NOT NULL,
  seat_no VARCHAR(8) NOT NULL,
  status VARCHAR(16) NOT NULL,
  hold_id VARCHAR(36) NULL,
  held_until DATETIME(6) NULL,
  PRIMARY KEY (screening_id, seat_no),
  CONSTRAINT fk_seats_screening FOREIGN KEY (screening_id) REFERENCES screenings(id),
  CONSTRAINT fk_seats_hold FOREIGN KEY (hold_id) REFERENCES seat_holds(hold_id)
);

CREATE TABLE IF NOT EXISTS payments (
  payment_id VARCHAR(36) PRIMARY KEY,
  hold_id VARCHAR(36) NOT NULL UNIQUE,
  screening_id VARCHAR(128) NOT NULL,
  queue_token VARCHAR(256) NOT NULL,
  amount INT NOT NULL,
  status VARCHAR(16) NOT NULL,
  pg_tx_id VARCHAR(128) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_payments_hold FOREIGN KEY (hold_id) REFERENCES seat_holds(hold_id)
);

CREATE TABLE IF NOT EXISTS reservations (
  reservation_id VARCHAR(36) PRIMARY KEY,
  screening_id VARCHAR(128) NOT NULL,
  queue_token VARCHAR(256) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  hold_id VARCHAR(36) NOT NULL UNIQUE,
  payment_id VARCHAR(36) NOT NULL UNIQUE,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_reservation_token (screening_id, queue_token),
  UNIQUE KEY uq_reservation_idempotency (screening_id, idempotency_key),
  CONSTRAINT fk_reservations_screening FOREIGN KEY (screening_id) REFERENCES screenings(id),
  CONSTRAINT fk_reservations_hold FOREIGN KEY (hold_id) REFERENCES seat_holds(hold_id),
  CONSTRAINT fk_reservations_payment FOREIGN KEY (payment_id) REFERENCES payments(payment_id)
);

CREATE TABLE IF NOT EXISTS reservation_seats (
  reservation_id VARCHAR(36) NOT NULL,
  screening_id VARCHAR(128) NOT NULL,
  seat_no VARCHAR(8) NOT NULL,
  PRIMARY KEY (reservation_id, seat_no),
  UNIQUE KEY uq_reservation_seat (screening_id, seat_no),
  CONSTRAINT fk_reservation_seats_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(reservation_id),
  CONSTRAINT fk_reservation_seats_seat FOREIGN KEY (screening_id, seat_no) REFERENCES screening_seats(screening_id, seat_no)
);
