import { ScreenHeader, MetricGrid, MetricCard, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";
import { M3Button, M3Tabs } from "../shell/M3Widgets";

/** M3Operations with nothing run yet. */
export function OperationsScreen() {
  return (
    <ScreenRoot>
      <ScreenHeader
        title="Operations"
        subtitle="What is running against the fleet, and what the fleet is ready for. Readiness is observed; no failover action exists in this build."
        actions={
          <>
            <M3Button emphasis="outlined">Job history</M3Button>
            <M3Button emphasis="filled">Schedule collection</M3Button>
          </>
        }
      />
      <MetricGrid>
        <MetricCard title="Jobs run" note="nothing run yet" />
        <MetricCard title="Success rate" note="no runs to measure" />
        <MetricCard title="Readiness checks" note="no cluster enrolled" />
        <MetricCard title="Alerts" note="nothing to alert on" />
      </MetricGrid>
      <M3Tabs
        ariaLabel="Operations sections"
        tabs={[
          {
            label: "HA & readiness",
            panel: (
              <EmptyPanel
                title="No HA pair or cluster enrolled"
                body="Readiness needs enrolled cluster members and a current health read from each one; neither
                      exists yet, so no cluster can be assessed."
              />
            ),
          },
          {
            label: "Jobs",
            panel: (
              <EmptyPanel
                title="No jobs yet"
                body="Nothing has run against the fleet, because the fleet is empty. Read jobs are class 0; backup
                      creation is the only class 1 write and runs under its own contract."
              />
            ),
          },
          {
            label: "Queue",
            panel: (
              <EmptyPanel
                title="Nothing queued"
                body="No job is scheduled to run against the fleet yet. A job appears here once it is scheduled
                      and before it starts."
              />
            ),
          },
          {
            label: "History",
            panel: (
              <EmptyPanel
                title="No job history"
                body="Job history accumulates only after jobs run against the fleet; none has run in this empty
                      database yet."
              />
            ),
          },
        ]}
      />
    </ScreenRoot>
  );
}
