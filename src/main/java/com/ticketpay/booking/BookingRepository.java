package com.ticketpay.booking;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from Booking b where b.id = :id")
  Optional<Booking> findByIdForUpdate(UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, Instant cutoff);

  Page<Booking> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable pageable);

  boolean existsByReference(String reference);
}
