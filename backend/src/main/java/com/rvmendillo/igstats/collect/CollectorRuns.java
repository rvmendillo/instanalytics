package com.rvmendillo.igstats.collect;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes one {@code collector_run} row per job execution, so a failing job
 * shows up as a failed row rather than as data that quietly stopped arriving.
 */
@Component
public class CollectorRuns {

    private final JdbcTemplate jdbc;

    public CollectorRuns(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Run start(long accountId, String collector) {
        Long id = jdbc.queryForObject(
                "insert into collector_run (account_id, collector) values (?, ?) returning id",
                Long.class, accountId, collector);
        return new Run(id);
    }

    /** Mutable tally for one run. Not thread-safe; one run, one thread. */
    public final class Run {
        private final long id;
        private int calls;
        private int written;
        private boolean partial;

        private Run(long id) {
            this.id = id;
        }

        public void call() {
            calls++;
        }

        public void wrote(int n) {
            written += n;
        }

        /** Something was skipped but the run as a whole carried on. */
        public void partial() {
            partial = true;
        }

        public int written() {
            return written;
        }

        public void ok() {
            finish(partial ? "PARTIAL" : "OK", null, null);
        }

        public void failed(Integer code, String message) {
            finish("FAILED", code, message);
        }

        private void finish(String status, Integer code, String message) {
            jdbc.update("""
                    update collector_run set finished_at = now(), status = ?, items_written = ?,
                           api_calls = ?, error_code = ?, error_message = ?
                    where id = ?
                    """, status, written, calls, code, message, id);
        }
    }
}
