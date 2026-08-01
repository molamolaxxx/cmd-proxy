package com.mola.cmd.proxy.app.acp.team;

import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.team.model.TeamDefinition;
import com.mola.cmd.proxy.app.acp.team.model.TeamMemberDefinition;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;
import java.util.function.Consumer;

@FunctionalInterface
public interface TeamMemberClientStarter {

    AcpClient start(TeamRuntime runtime, TeamMemberDefinition member,
                    TeamSourceRobotSnapshot sourceSnapshot,
                    TeamMemberStartOptions options,
                    Consumer<AcpClient> onClientCreated) throws Exception;
}
