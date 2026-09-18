package com.mola.cmd.proxy.app.acp.schedule;

import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleConfig;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduledTask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;

/** Durable, append-only audit journal for every schedule execution attempt. */
final class ScheduleExecutionJournal {
    static final String DATABASE_FILE = "executions.db";

    private final String jdbcUrl;

    ScheduleExecutionJournal(Path schedulesBaseDir) {
        try {
            Files.createDirectories(schedulesBaseDir);
            Class.forName("org.sqlite.JDBC");
            this.jdbcUrl = "jdbc:sqlite:"
                    + schedulesBaseDir.resolve(DATABASE_FILE).toAbsolutePath();
            try (Connection connection = open();
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("CREATE TABLE IF NOT EXISTS schedule_execution("
                        + "id TEXT PRIMARY KEY, task_id TEXT NOT NULL, title TEXT NOT NULL, "
                        + "owner_scope TEXT NOT NULL, owner_path TEXT NOT NULL, owner_id TEXT NOT NULL, "
                        + "robot_name TEXT, team_id TEXT, team_member_id TEXT, group_name TEXT, "
                        + "schedule_type TEXT, schedule_expr TEXT, scheduled_at INTEGER NOT NULL, "
                        + "started_at INTEGER NOT NULL, finished_at INTEGER, delay_millis INTEGER NOT NULL, "
                        + "attempt INTEGER NOT NULL, status TEXT NOT NULL, result_code TEXT, detail TEXT)");
                statement.execute("CREATE INDEX IF NOT EXISTS schedule_execution_task_time "
                        + "ON schedule_execution(task_id,started_at DESC)");
                statement.execute("CREATE INDEX IF NOT EXISTS schedule_execution_owner_time "
                        + "ON schedule_execution(owner_path,started_at DESC)");
                statement.execute("CREATE INDEX IF NOT EXISTS schedule_execution_status_time "
                        + "ON schedule_execution(status,started_at DESC)");
            }
        } catch (Exception error) {
            throw new IllegalStateException("schedule execution journal unavailable", error);
        }
    }

    synchronized String begin(ScheduleOwnerKey owner, ScheduledTask task,
                              int attempt, long startedAt) {
        String id = UUID.randomUUID().toString();
        ScheduleConfig schedule = task.getSchedule();
        try (Connection connection = open();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO schedule_execution(id,task_id,title,owner_scope,owner_path,"
                             + "owner_id,robot_name,team_id,team_member_id,group_name,schedule_type,"
                             + "schedule_expr,scheduled_at,started_at,delay_millis,attempt,status) "
                             + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            bind(insert, id, task.getId(), task.getTitle(), owner.getScope().name(),
                    owner.getPersistencePath(), owner.getOwnerId(), owner.getRobotName(),
                    owner.getTeamId(), owner.getTeamMemberId(), task.getGroupName(),
                    schedule == null ? null : schedule.getType(),
                    schedule == null ? null : schedule.getExpr(), task.getNextRunAt(), startedAt,
                    Math.max(0L, startedAt - task.getNextRunAt()), attempt, "RUNNING");
            insert.executeUpdate();
            return id;
        } catch (Exception error) {
            throw new IllegalStateException("schedule execution begin persistence failed", error);
        }
    }

    synchronized void finish(String id, String status, String resultCode,
                             String detail, long finishedAt) {
        try (Connection connection = open();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE schedule_execution SET finished_at=?,status=?,result_code=?,detail=? "
                             + "WHERE id=?")) {
            bind(update, finishedAt, status, resultCode, truncate(detail), id);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("schedule execution record not found: " + id);
            }
        } catch (Exception error) {
            throw new IllegalStateException("schedule execution finish persistence failed", error);
        }
    }

    private Connection open() throws Exception {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private static void bind(PreparedStatement statement, Object... values)
            throws Exception {
        for (int i = 0; i < values.length; i++) {
            statement.setObject(i + 1, values[i]);
        }
    }

    private static String truncate(String detail) {
        if (detail == null || detail.length() <= 4000) return detail;
        return detail.substring(0, 4000);
    }
}
