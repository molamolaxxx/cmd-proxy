package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientIdentity;
import com.mola.cmd.proxy.app.acp.schedule.ScheduleTaskManager;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleConfig;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.team.model.TeamOperationRecord;
import com.mola.cmd.proxy.app.acp.team.model.TeamState;
import com.mola.cmd.proxy.app.acp.team.model.TeamTombstone;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.*;

public class TeamResourceReaperTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void reaperClosesOrphansAndRemovesExpiredMetadataArchiveAndSchedule()
            throws Exception {
        Path teams = temporaryFolder.newFolder("teams").toPath();
        Path sessionTeam = temporaryFolder.newFolder("session-team").toPath();
        Path archive = temporaryFolder.newFolder("archive").toPath();
        Path scheduleRoot = temporaryFolder.newFolder("schedule").toPath();
        TeamStore store = new TeamStore(teams);
        long now = System.currentTimeMillis();
        store.saveOperation(new TeamOperationRecord(
                "request-old", TeamOperationRecord.Operation.DELETE,
                "hash", "team-old", TeamOperationRecord.Status.SUCCEEDED,
                "{}", now - 2_000L, now - 1_000L));
        store.saveTombstone(new TeamTombstone(
                "team-old", "request-old", TeamState.DELETED,
                now - 2_000L, now - 1_000L, Collections.emptyList()));

        Path history = sessionTeam.resolve("team-old/member/session/turn.json");
        Files.createDirectories(history.getParent());
        Files.write(history, "{}".getBytes(StandardCharsets.UTF_8));
        TeamHistoryArchiver archiver =
                new TeamHistoryArchiver(sessionTeam, archive);
        archiver.archive("team-old", now - 1_000L);

        TeamClientRegistry registry = new TeamClientRegistry();
        AcpClient orphan = client("team-orphan", "member-1");
        registry.register("team-orphan", "member-1", orphan);
        ScheduleTaskManager schedule = new ScheduleTaskManager(scheduleRoot);
        schedule.createTask(ScheduleOwnerKey.team(
                        "owner", "team-orphan", "member-1", "Robot"),
                "orphan", "prompt", new ScheduleConfig("once", "+1h"));

        TeamManager manager = new TeamManager(
                store, registry, spec -> {
                    throw new TeamSourceResolutionException(
                            com.mola.cmd.proxy.app.acp.team.model.TeamErrorCode
                                    .SOURCE_ROBOT_NOT_FOUND, "unused");
                }, event -> { }, null, archiver, 1_000L);
        manager.setScheduleCleanup(schedule::cleanupTeam);
        manager.setScheduleOrphanCleanup(
                schedule::cleanupOrphanTeams, schedule::teamOwnerCount,
                schedule::teamOwnerCount);

        new TeamResourceReaper(manager).runOnce();

        assertEquals(0, registry.size());
        assertEquals(AbstractAcpClient.State.CLOSED, orphan.getState());
        assertFalse(store.loadOperation("request-old").isPresent());
        assertFalse(store.loadTombstone("team-old").isPresent());
        assertFalse(Files.exists(archive.resolve("team-old")));
        assertEquals(0, schedule.teamOwnerCount());
        assertTrue(manager.resourceSnapshot().isZero());
        manager.close();
    }

    private static AcpClient client(String teamId, String memberId) {
        AcpRobotParam robot = new AcpRobotParam();
        robot.setName("Robot");
        return new AcpClient(".", AcpClientIdentity.team(
                "team-acp-" + memberId, "team-acp-instance",
                "team/" + teamId + "/" + memberId, "owner",
                teamId, memberId, "Robot"), robot);
    }
}
