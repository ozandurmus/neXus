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
      <M3Tabs ariaLabel="Operations sections" tabs={["HA & readiness", "Jobs", "Queue", "History"]} />
      <MetricGrid>
        <MetricCard title="Jobs run" note="nothing run yet" />
        <MetricCard title="Success rate" note="no runs to measure" />
        <MetricCard title="Readiness checks" note="no cluster enrolled" />
        <MetricCard title="Alerts" note="nothing to alert on" />
      </MetricGrid>
      <EmptyPanel
        title="No jobs yet"
        body="Nothing has run against the fleet, because the fleet is empty. Read jobs are class 0; backup
              creation is the only class 1 write and runs under its own contract."
      />
    </ScreenRoot>
  );
}
