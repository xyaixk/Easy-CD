package com.easy.cd.terminal;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceLogCommandBuilderTest {

    private final ServiceLogCommandBuilder builder = new ServiceLogCommandBuilder();

    @Test
    void staticAggregateReadsOnlyResolvedRunningTasks() {
        String command = builder.buildAggregate(
                "api", Arrays.asList("running1", "running2"), 500, false);

        assertTrue(command.contains("running1"));
        assertTrue(command.contains("running2"));
        assertTrue(command.contains("--tail 500"));
        assertTrue(command.contains("--timestamps"));
        assertTrue(command.contains("mktemp -d"));
        assertTrue(command.contains("LC_ALL=C sort -k1,1"));
        assertFalse(command.contains("docker service logs api"));
    }

    @Test
    void staticAggregateSortsSingleRunningTaskByDockerTimestampToo() {
        String command = builder.buildAggregate(
                "api", Collections.singletonList("running1"), 500, false);

        assertTrue(command.contains("docker service logs running1"));
        assertTrue(command.contains("LC_ALL=C sort -k1,1"));
    }

    @Test
    void liveAggregateFollowsOnlyNewServiceOutputAndFutureReplacementTasks() {
        String command = builder.buildAggregate(
                "api", Collections.singletonList("running1"), 500, true);

        assertTrue(command.contains("docker service logs api"));
        assertTrue(command.contains("--tail 0"));
        assertTrue(command.contains("--follow"));
        assertFalse(command.contains("running1"));
    }

    @Test
    void selectedTaskAlwaysUsesTaskSelector() {
        String command = builder.buildTask("history1", 250, false);

        assertTrue(command.contains("docker service logs history1"));
        assertTrue(command.contains("--tail 250"));
        assertFalse(command.contains("--follow"));
    }

    @Test
    void validatesIdentifiersAndBoundsTail() {
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildTask("bad; rm -rf", 500, false));
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildAggregate("bad service", Collections.emptyList(), 500, true));

        String command = builder.buildTask("task1", 50_000, false);
        assertTrue(command.contains("--tail 1000"));
    }
}
