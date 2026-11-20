package com.rvmendillo.igstats.collect;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class StoryTimeTest {

    @Test
    void parsesInstagramOffsetFormat() {
        // ISO_OFFSET_DATE_TIME rejects "+0000"; Instagram always sends it.
        assertThat(StoryInsightsCollector.parseIgTime("2026-11-20T07:00:00+0000"))
                .isEqualTo(Instant.parse("2026-11-20T07:00:00Z"));
    }

    @Test
    void storyDayIsLocalNotUtc() {
        // 7am in Manila is 11pm the previous day in UTC. If frames were bucketed
        // by UTC day, one morning's story would split across two days.
        Instant sevenAmManila = StoryInsightsCollector.parseIgTime("2026-11-19T23:00:00+0000");
        assertThat(sevenAmManila.atZone(ZoneId.of("Asia/Manila")).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 11, 20));
    }
}
