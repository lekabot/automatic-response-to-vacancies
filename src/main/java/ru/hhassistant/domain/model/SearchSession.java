package ru.hhassistant.domain.model;

import java.time.Instant;

public record SearchSession(
  long chatId,
  Instant startedAt,
  Integer lastHourlyReportSlot
) {

  public int currentHourlySlot(Instant now) {
    long elapsed = now.getEpochSecond() - startedAt.getEpochSecond();
    return (int) (elapsed / 3600);
  }

  public boolean isHourlyReportDue(Instant now) {
    int current = currentHourlySlot(now);
    if (current < 1) return false;
    return lastHourlyReportSlot == null || current > lastHourlyReportSlot;
  }
}
