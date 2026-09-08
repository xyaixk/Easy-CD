package com.easy.cd.util;

import com.easy.cd.util.SshExecutor.SshHost;
import com.easy.cd.util.SshExecutor.SshResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SshExecutorFailoverTest {

    private static final String COMMAND = "docker pull registry.example.com/team/api:new";

    @Test
    void commandTimeoutIsNotReplayedOnAnotherManager() {
        SshExecutor executor = spy(new SshExecutor());
        doReturn(SshResult.timeout("timed out"))
                .when(executor).executeCommand(any(SshHost.class), eq(COMMAND), anyInt());

        SshResult result = executor.executeCommandWithFailover(hosts(), COMMAND, 1_000);

        assertTrue(result.isTimeout());
        verify(executor, times(1)).executeCommand(any(SshHost.class), eq(COMMAND), anyInt());
    }

    @Test
    void connectionFailureStillUsesTheNextManager() {
        SshExecutor executor = spy(new SshExecutor());
        doReturn(SshResult.connectionFailure("connection refused"), new SshResult(0, "ok", ""))
                .when(executor).executeCommand(any(SshHost.class), eq(COMMAND), anyInt());

        SshResult result = executor.executeCommandWithFailover(hosts(), COMMAND, 1_000);

        assertTrue(result.isSuccess());
        verify(executor, times(2)).executeCommand(any(SshHost.class), eq(COMMAND), anyInt());
    }

    private List<SshHost> hosts() {
        return Arrays.asList(
                new SshHost("manager-1", 22, "root", null, null),
                new SshHost("manager-2", 22, "root", null, null));
    }
}
