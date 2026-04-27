package tgbotgpt.service.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiHealthIndicatorTest {

    @Test
    void shouldReportUnknownWhenNoOutcomesObserved() {
        OpenAiHealthIndicator indicator = newIndicator(60);

        Health health = indicator.health();

        assertEquals(Status.UNKNOWN, health.getStatus());
    }

    @Test
    void shouldReportUpAfterSuccess() {
        OpenAiHealthIndicator indicator = newIndicator(60);
        indicator.recordSuccess();

        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void shouldReportDegradedWhenRecentFailure() {
        OpenAiHealthIndicator indicator = newIndicator(60);
        indicator.recordFailure("BoomException");

        Health health = indicator.health();

        assertEquals("DEGRADED", health.getStatus().getCode());
        assertEquals("BoomException", health.getDetails().get("lastFailure"));
    }

    @Test
    void shouldReportUnknownWhenFailureIsStale() throws InterruptedException {
        OpenAiHealthIndicator indicator = newIndicator(0);  // freshness=0 → instantly stale
        indicator.recordFailure("Stale");
        Thread.sleep(5);

        assertEquals(Status.UNKNOWN, indicator.health().getStatus());
    }

    @Test
    void shouldReportUnknownWhenSuccessIsStale() throws InterruptedException {
        OpenAiHealthIndicator indicator = newIndicator(0);  // freshness=0 → instantly stale
        indicator.recordSuccess();
        Thread.sleep(5);

        assertEquals(Status.UNKNOWN, indicator.health().getStatus());
    }

    private OpenAiHealthIndicator newIndicator(long freshnessSeconds) {
        OpenAiHealthIndicator indicator = new OpenAiHealthIndicator();
        ReflectionTestUtils.setField(indicator, "freshnessSeconds", freshnessSeconds);
        return indicator;
    }
}
