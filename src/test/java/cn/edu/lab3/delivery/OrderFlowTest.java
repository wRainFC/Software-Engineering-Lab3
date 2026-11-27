package cn.edu.lab3.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderFlowTest {
  private final Db db = mock(Db.class);
  private final Actor merchant = new Actor(8, "merchant", "MERCHANT");
  private final OrderFlow.Command accept =
      new OrderFlow.Command("ACCEPT", 0, "test", "", null, null);

  private OrderFlow flowAt(long millis, String status) {
    when(db.one("SELECT * FROM orders WHERE id=? FOR UPDATE", 1L))
        .thenReturn(Map.of("id", 1L, "shop_id", 2L, "status", status, "deadline", 300000L,
            "version", 0L, "consumed", false, "reason", ""));
    when(db.number("SELECT owner_id FROM shop WHERE id=?", 2L)).thenReturn(8L);
    when(db.rows("SELECT * FROM action_receipt WHERE operator_id=? AND request_no=?", 8L, "test"))
        .thenReturn(List.of());
    when(db.update(anyString(), any(Object[].class))).thenReturn(1);
    return new OrderFlow(db, mock(Audit.class),
        Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC));
  }

  @Test
  void lastMillisecondBeforeDeadlineAllowsAcceptance() {
    assertEquals(new OrderFlow.Result("ACCEPTED", 1),
        flowAt(299999, "WAITING").apply(merchant, 1, accept));
  }

  @Test
  void deadlineBelongsToTimeoutEvenBeforeSchedulerRuns() {
    var flow = flowAt(300000, "WAITING");
    Problem p = assertThrows(Problem.class, () -> flow.apply(merchant, 1, accept));
    assertEquals(409, p.status);
  }

  @Test
  void terminalOrderRejectsAcceptance() {
    var flow = flowAt(10000, "CANCELLED");
    assertEquals(409, assertThrows(Problem.class, () -> flow.apply(merchant, 1, accept)).status);
  }

  @Test
  void wrongRoleCannotAccept() {
    var flow = flowAt(10000, "WAITING");
    var customer = new Actor(7, "customer", "CUSTOMER");
    assertEquals(403, assertThrows(Problem.class, () -> flow.apply(customer, 1, accept)).status);
  }
}
