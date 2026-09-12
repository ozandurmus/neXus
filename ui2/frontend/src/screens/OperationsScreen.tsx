import { ScreenHeader, MetricGrid, MetricCard, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";

/** M3Operations with nothing run yet. */
export function OperationsScreen() {
  return (
    <ScreenRoot>
      <ScreenHeader
        title="Operations"
        subtitle="What is running against the fleet, and what the fleet is ready for. Readiness is observed; no failover action exists in this build."
      />
      <MetricGrid>
        <MetricCard title="Jobs run" note="nothing run yet" />
        <MetricCard title="Success rate" note="no runs to measure" />
        <MetricCard title="Readiness checks" note="no cluster enrolled" />
        <MetricCard title="Alerts" note="nothing to alert on" />
      </MetricGrid>
      <EmptyPanel title="No jobs yet" body="Nothing has run against the fleet, because the fleet is empty." />
    </ScreenRoot>
  );
}
