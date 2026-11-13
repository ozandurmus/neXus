import { Component, Fragment, type ErrorInfo, type ReactNode } from "react";

import { M3Button } from "./M3Widgets";
import { StatePanel } from "./States";

/**
 * The product's one panel-level error boundary (robustness pass 2026-11-13). A panel that throws while it renders --
 * typically because an endpoint answered with a body of an unexpected shape -- is replaced by a contained
 * "This section could not be shown" state with Retry, instead of unmounting the whole application. The shell (menu, top
 * bar, sign-out) sits outside every boundary, so it keeps working.
 *
 * Wrapped around every screen body (App), every tab panel (M3Tabs) and every Administration page section.
 *
 * Retry mounts the section's children again from scratch (a fresh `key`), so the section reads its data again.
 * `resetKey` clears a shown failure by itself when the viewer moves on (another screen, another tab, another section).
 *
 * The console line names the section and the error's class only. The error's message and the stack are left out on
 * purpose: a message built from a response can carry the response's contents, and those must not reach a log.
 */
interface Props {
  readonly children: ReactNode;
  /** The section's name, for the log line only (never shown). */
  readonly label?: string;
  /** When this value changes, a shown failure is cleared and the children are mounted again. */
  readonly resetKey?: string | number;
}

interface State {
  readonly failed: boolean;
  readonly attempt: number;
}

export class PanelErrorBoundary extends Component<Props, State> {
  state: State = { failed: false, attempt: 0 };

  static getDerivedStateFromError(): Partial<State> {
    return { failed: true };
  }

  componentDidCatch(error: unknown, _info: ErrorInfo): void {
    const kind = error instanceof Error && error.name ? error.name : typeof error;
    console.error(`Section could not be shown${this.props.label ? ` (${this.props.label})` : ""}: ${kind}`);
  }

  componentDidUpdate(prev: Props): void {
    if (this.state.failed && prev.resetKey !== this.props.resetKey) this.retry();
  }

  private retry = () => this.setState((s) => ({ failed: false, attempt: s.attempt + 1 }));

  render(): ReactNode {
    if (this.state.failed) {
      return (
        <StatePanel variant="error" announce="alert" title="This section could not be shown"
          body="Something in this section went wrong while it was being drawn. The rest of the application still works. Retry loads the section again."
          action={<M3Button emphasis="outlined" onClick={this.retry}>Retry</M3Button>} />
      );
    }
    return <Fragment key={this.state.attempt}>{this.props.children}</Fragment>;
  }
}
