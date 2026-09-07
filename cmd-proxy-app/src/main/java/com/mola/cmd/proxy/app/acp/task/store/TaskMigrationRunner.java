package com.mola.cmd.proxy.app.acp.task.store;

import java.sql.*;

/** Ordered, transactional schema installation. Never deletes application data. */
public final class TaskMigrationRunner {
    private TaskMigrationRunner() { }
    public static void migrate(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_migration(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            try (ResultSet r = s.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migration")) {
                if (r.next() && r.getInt(1) > 3) throw new SQLException("Task database schema is newer than this application");
            }
            try (ResultSet r = s.executeQuery("SELECT 1 FROM schema_migration WHERE version=1")) {
                if (r.next()) { migrateReceipts(c); migrateOutboxPayload(c); return; }
            }
        }
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE task(id TEXT PRIMARY KEY, status TEXT NOT NULL, revision INTEGER NOT NULL, content_version INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, snapshot TEXT NOT NULL)");
            s.execute("CREATE INDEX task_status_updated ON task(status,updated_at)");
            s.execute("CREATE TABLE task_history(task_id TEXT NOT NULL REFERENCES task(id), revision INTEGER NOT NULL, content_version INTEGER NOT NULL, snapshot TEXT NOT NULL, PRIMARY KEY(task_id,revision))");
            s.execute("CREATE TABLE task_comment(id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES task(id), created_at TEXT NOT NULL, snapshot TEXT NOT NULL)");
            s.execute("CREATE INDEX task_comment_order ON task_comment(task_id,created_at,id)");
            s.execute("CREATE TABLE task_attachment(id TEXT PRIMARY KEY, storage_key TEXT NOT NULL UNIQUE, created_at TEXT NOT NULL, snapshot TEXT NOT NULL)");
            s.execute("CREATE TABLE task_content_attachment(task_id TEXT NOT NULL REFERENCES task(id), content_version INTEGER NOT NULL, attachment_id TEXT NOT NULL REFERENCES task_attachment(id), PRIMARY KEY(task_id,content_version,attachment_id))");
            s.execute("CREATE TABLE task_comment_attachment(comment_id TEXT NOT NULL REFERENCES task_comment(id), attachment_id TEXT NOT NULL REFERENCES task_attachment(id), PRIMARY KEY(comment_id,attachment_id))");
            s.execute("CREATE TABLE task_request_dedup(operation TEXT NOT NULL, request_id TEXT NOT NULL, digest TEXT NOT NULL, result TEXT NOT NULL, PRIMARY KEY(operation,request_id))");
            s.execute("CREATE TABLE task_outbox(event_id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES task(id), content_version INTEGER NOT NULL, event_type TEXT NOT NULL, seq INTEGER NOT NULL, assignee TEXT, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT 0, lease_until INTEGER NOT NULL DEFAULT 0, lease_token TEXT, error TEXT, receipt TEXT, UNIQUE(task_id,seq))");
            s.execute("CREATE INDEX task_outbox_due ON task_outbox(state,next_attempt_at)");
            s.execute("CREATE TABLE task_receipt(event_id TEXT PRIMARY KEY, receipt TEXT NOT NULL, payload TEXT NOT NULL, completed INTEGER NOT NULL DEFAULT 0)");
            s.execute("INSERT INTO schema_migration VALUES(1,strftime('%Y-%m-%dT%H:%M:%fZ','now'))");
            c.commit();
        } catch (SQLException e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(true); }
        migrateReceipts(c);
        migrateOutboxPayload(c);
    }
    private static void migrateReceipts(Connection c) throws SQLException {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT 1 FROM schema_migration WHERE version=2")){if(r.next())return;}
        c.setAutoCommit(false);
        try(Statement s=c.createStatement()){
            s.execute("ALTER TABLE task_receipt ADD COLUMN lease_token TEXT");
            s.execute("ALTER TABLE task_receipt ADD COLUMN lease_until INTEGER NOT NULL DEFAULT 0");
            s.execute("ALTER TABLE task_receipt ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0");
            s.execute("INSERT INTO schema_migration VALUES(2,strftime('%Y-%m-%dT%H:%M:%fZ','now'))");
            c.commit();
        }catch(SQLException e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
    }

    private static void migrateOutboxPayload(Connection c) throws SQLException {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery(
                "SELECT 1 FROM schema_migration WHERE version=3")){if(r.next())return;}
        c.setAutoCommit(false);
        try(Statement s=c.createStatement()){
            s.execute("ALTER TABLE task_outbox ADD COLUMN payload TEXT");
            s.execute("INSERT INTO schema_migration VALUES(3,strftime('%Y-%m-%dT%H:%M:%fZ','now'))");
            c.commit();
        }catch(SQLException e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
    }
}
