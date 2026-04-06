package ru.hhassistant.domain.port;

import ru.hhassistant.domain.model.SearchSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;


public interface SearchSessionRepository {
  void start(long chatId, Instant startedAt);

  boolean clear(long chatId);

  Optional<SearchSession> find(long chatId);

  List<SearchSession> findAllActive();

  Optional<HourlySlotClaim> tryClaimHourlySlot(long chatId, Instant now);

  void revertHourlySlot(long chatId, Integer previousSlot);

  record HourlySlotClaim(int claimedSlot, Integer previousSlot) {
  }
}
