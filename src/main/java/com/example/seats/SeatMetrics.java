package com.example.seats;

import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** seats_available{show_id} gauge, refreshed from the DB every second (source of truth = seats table). */
@Component
public class SeatMetrics {
  private final JdbcTemplate jdbc;
  private final MultiGauge gauge;

  public SeatMetrics(JdbcTemplate jdbc, MeterRegistry reg) {
    this.jdbc = jdbc;
    this.gauge = MultiGauge.builder("seats_available").description("Seats currently available per show").register(reg);
  }

  @Scheduled(fixedDelay = 1000)
  public void refresh() {
    try {
      List<MultiGauge.Row<?>> rows = new ArrayList<>();
      jdbc.query("select show_id, count(*) filter (where status='available') from seats group by show_id",
          rs -> { rows.add(MultiGauge.Row.of(Tags.of("show_id", rs.getString(1)), rs.getLong(2))); });
      gauge.register(rows, true);
    } catch (Exception ignored) { /* DB down: keep last values; /readyz reports it */ }
  }
}
