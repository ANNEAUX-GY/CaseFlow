package com.caseflow.bootstrap;

import com.baomidou.mybatisplus.annotation.TableField;
import com.caseflow.entity.CaseDeadlineState;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.DatabaseMetaData;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SchemaMigrationDeadlineTest {
    @Test
    void oldDeadlineTableGetsEveryMissingAdministrativeColumn() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:deadline-columns;DB_CLOSE_DELAY=-1");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE case_deadline_state (case_id BIGINT PRIMARY KEY, "
                    + "detention_term VARCHAR(8), pretrial_received_at TIMESTAMP, legal_received_at TIMESTAMP, "
                    + "pretrial_sent_at TIMESTAMP, legal_sent_at TIMESTAMP, submitted_at TIMESTAMP, "
                    + "last_summons_at TIMESTAMP, updated_at TIMESTAMP)");
            st.execute("INSERT INTO case_deadline_state (case_id, detention_term) VALUES (42, '7')");
            SchemaMigration migration = new SchemaMigration();
            migration.ensureColumns(conn, deadlineColumns(conn), "case_deadline_state");
            migration.ensureColumns(conn, deadlineColumns(conn), "case_deadline_state");
            Set<String> columns = deadlineColumns(conn);
            for (String column : new String[] {"admin_30_doc_at", "admin_extended_at",
                    "admin_60_doc_at", "admin_check_3_at", "admin_check_10_at", "admin_check_20_at"}) {
                assertTrue(columns.contains("case_deadline_state." + column), column);
            }
            try (ResultSet rs = st.executeQuery("SELECT * FROM case_deadline_state WHERE case_id=42")) {
                assertTrue(rs.next());
                assertEquals("7", rs.getString("detention_term"));
                assertNull(rs.getTimestamp("admin_30_doc_at"));
            }
            try (ResultSet rs = st.executeQuery("SELECT admin_30_doc_at, admin_extended_at, "
                    + "admin_60_doc_at, admin_check_3_at, admin_check_10_at, admin_check_20_at "
                    + "FROM case_deadline_state WHERE case_id=42")) {
                assertTrue(rs.next());
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void numericAdminFieldsMapToUnderscoredDatabaseColumns() throws Exception {
        String[][] fields = {{"admin30DocAt", "admin_30_doc_at"},
                {"admin60DocAt", "admin_60_doc_at"},
                {"adminCheck3At", "admin_check_3_at"},
                {"adminCheck10At", "admin_check_10_at"},
                {"adminCheck20At", "admin_check_20_at"}};
        for (String[] pair : fields) {
            Field field = CaseDeadlineState.class.getDeclaredField(pair[0]);
            assertEquals(pair[1], field.getAnnotation(TableField.class).value());
        }
    }

    private Set<String> deadlineColumns(Connection conn) throws Exception {
        Set<String> columns = new HashSet<>();
        DatabaseMetaData metadata = conn.getMetaData();
        try (ResultSet rs = metadata.getColumns(conn.getCatalog(), null, "CASE_DEADLINE_STATE", null)) {
            while (rs.next()) columns.add("case_deadline_state." + rs.getString("COLUMN_NAME").toLowerCase());
        }
        return columns;
    }

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
