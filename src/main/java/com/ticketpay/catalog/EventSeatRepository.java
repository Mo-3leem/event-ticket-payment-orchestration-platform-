package com.ticketpay.catalog;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EventSeatRepository extends JpaRepository<EventSeat, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select es from EventSeat es where es.id = :id")
  Optional<EventSeat> findByIdForUpdate(UUID id);

  List<EventSeat> findByEventId(UUID eventId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<EventSeat> findByStatusAndHoldExpiresAtBefore(EventSeatStatus status, Instant cutoff);
}
