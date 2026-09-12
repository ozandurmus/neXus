import { ScreenHeader, MetricGrid, MetricCard, EmptyPanel, ScreenRoot } from "../shell/ScreenLayout";

/** M3Compliance with no framework assigned and no control evidence yet. */
export function ComplianceScreen() {
  return (
    <ScreenRoot>
      <ScreenHeader title="Compliance" subtitle="No framework assigned · nothing assessed yet" />
      <MetricGrid>
        <MetricCard title="Control coverage" note="no framework assigned" />
        <MetricCard title="Controls covered" note="nothing assessed" />
        <MetricCard title="Subjects assessed" note="no devices enrolled" />
        <MetricCard title="Open findings" note="nothing to find" />
      </MetricGrid>
      <EmptyPanel
        title="No compliance evidence yet"
        body="Assigning a framework and enrolling devices are both required before a control can be assessed."
      />
    </ScreenRoot>
  );
}
