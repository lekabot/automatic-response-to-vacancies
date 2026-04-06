package ru.hhassistant.infrastructure.db;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class DbConverters {
  private DbConverters() {
  }

  static OffsetDateTime toOdt(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  static Instant toInstant(OffsetDateTime odt) {
    return odt == null ? null : odt.toInstant();
  }
}
