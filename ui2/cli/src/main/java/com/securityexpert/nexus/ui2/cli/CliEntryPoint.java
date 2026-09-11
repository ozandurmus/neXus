package com.securityexpert.nexus.ui2.cli;

import com.securityexpert.nexus.ui2.jobs.JobState;

/**
 * A thin typed command entry point using the same application ports as
 * the other roles (architecture §8.3; contract §2 cli row).
 */
public final class CliEntryPoint {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("usage: cli <command>");
            return;
        }
        System.out.println("known job states: " + java.util.Arrays.toString(JobState.values()));
    }
}
