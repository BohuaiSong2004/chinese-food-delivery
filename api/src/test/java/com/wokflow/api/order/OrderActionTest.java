package com.wokflow.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderActionTest {
    @Test
    void terminalStatusesHaveNoWayOut() {
        for (OrderAction a : OrderAction.values()) {
            for (OrderStatus from : a.from) {
                assertThat(from.terminal()).as(a + " from " + from).isFalse();
            }
        }
    }

    @Test
    void everyNonTerminalStatusCanMoveOn() {
        Set<OrderStatus> reachable = EnumSet.noneOf(OrderStatus.class);
        for (OrderAction a : OrderAction.values()) {
            reachable.addAll(a.from);
        }
        for (OrderStatus s : OrderStatus.values()) {
            assertThat(reachable.contains(s)).as(s.name()).isEqualTo(!s.terminal());
        }
    }

    @Test
    void customersCannotCancelOnceTheKitchenAccepted() {
        assertThat(OrderAction.CANCEL.from).doesNotContain(OrderStatus.ACCEPTED, OrderStatus.READY, OrderStatus.PICKED_UP);
        assertThat(OrderAction.CLAIM.target(OrderStatus.READY)).isEqualTo(OrderStatus.READY);
    }

    @Test
    void ifMatchParsing() {
        assertThat(Versions.parse(null)).isNull();
        assertThat(Versions.parse("\"4\"")).isEqualTo(4);
        assertThat(Versions.parse("W/\"5\"")).isEqualTo(5);
        assertThatThrownBy(() -> Versions.parse("abc")).hasMessageContaining("If-Match");
    }
}
