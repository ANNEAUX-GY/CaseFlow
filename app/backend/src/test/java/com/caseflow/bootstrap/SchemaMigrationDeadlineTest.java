package com.caseflow.bootstrap;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SchemaMigrationDeadlineTest {
    @Test
    void oldReceiptsBecomeTransfersThatStillNeedReviewConfirmation() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:deadline-migration;DB_CLOSE_DELAY=-1");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE case_deadline_state (case_id BIGINT PRIMARY KEY, "
                    + "pretrial_received_at TIMESTAMP, legal_received_at TIMESTAMP, "
                    + "pretrial_sent_at TIMESTAMP, legal_sent_at TIMESTAMP)");
            st.execute("INSERT INTO case_deadline_state (case_id, pretrial_received_at) "
                    + "VALUES (1, TIMESTAMP '2026-01-03 09:00:00')");
            st.execute("INSERT INTO case_deadline_state (case_id, legal_sent_at, legal_received_at) "
                    + "VALUES (2, TIMESTAMP '2026-01-03 09:00:00', TIMESTAMP '2026-01-04 09:00:00')");
            Set<String> oldColumns = new HashSet<>();
            oldColumns.add("case_deadline_state.pretrial_received_at");
            oldColumns.add("case_deadline_state.legal_received_at");
            SchemaMigration migration = new SchemaMigration();
            migration.migrateLegacyDeadlineReceipts(conn, oldColumns);
            migration.migrateLegacyDeadlineReceipts(conn, oldColumns);
            try (ResultSet rs = st.executeQuery("SELECT * FROM case_deadline_state ORDER BY case_id")) {
                assertTrue(rs.next());
                assertEquals("2026-01-03 09:00:00.0", rs.getTimestamp("pretrial_sent_at").toString());
                assertNull(rs.getTimestamp("pretrial_received_at"));
                assertTrue(rs.next());
                assertEquals("2026-01-04 09:00:00.0", rs.getTimestamp("legal_received_at").toString());
                assertFalse(rs.next());
            }
        }
    }
}
