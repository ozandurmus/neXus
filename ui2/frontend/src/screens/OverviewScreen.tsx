import { ScreenHeader, MetricGrid, MetricCard, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";

/** M3Overview with nothing collected: the database is empty. */
export function OverviewScreen() {
  return (
    <ScreenRoot>
      <ScreenHeader title="Operational posture" subtitle="0 devices enrolled · nothing collected yet" />
      <MetricGrid>
        <MetricCard title="Network inventory" note="none enrolled" />
        <MetricCard title="Configuration" note="no evidence yet" />
        <MetricCard title="Local overrides" note="nothing to compare" />
        <MetricCard title="Effective drift" note="nothing to compare" />
      </MetricGrid>
      <EmptyPanel
        title="No evidence yet"
        body="The database is empty and nothing has been collected. Enrol a device from Administration to
              begin; no device is contacted until the Product Owner's collection direction is given."
      />
    </ScreenRoot>
  );
}
