"""OP.2.0 execution state machine -- four non-terminal states, six terminal.

Mirrors the frozen contract's "Execution state machine" section verbatim.
No state exists here for observability alone; every state differs from its
neighbours in restart reconciliation, cancellation legality or lock
behaviour (the contract's own review removed ``LOCKING``/``LOCKED``/
``EVALUATING``/``VERIFYING``/``SUCCEEDED_WITH_WARNINGS`` for failing that
test -- they are not reintroduced here).
"""
from __future__ import annotations

from enum import Enum


class ActionState(str, Enum):
    CREATED = "CREATED"
    PREFLIGHTING = "PREFLIGHTING"
    AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION"
    EXECUTING = "EXECUTING"
    NOT_ELIGIBLE = "NOT_ELIGIBLE"
    ABORTED_PRE_MUTATION = "ABORTED_PRE_MUTATION"
    CANCELLED = "CANCELLED"
    SUCCEEDED = "SUCCEEDED"
    FAILED_NO_CHANGE = "FAILED_NO_CHANGE"
    OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"


NON_TERMINAL_STATES: frozenset[ActionState] = frozenset({
    ActionState.CREATED,
    ActionState.PREFLIGHTING,
    ActionState.AWAITING_CONFIRMATION,
    ActionState.EXECUTING,
})

TERMINAL_STATES: frozenset[ActionState] = frozenset({
    ActionState.NOT_ELIGIBLE,
    ActionState.ABORTED_PRE_MUTATION,
    ActionState.CANCELLED,
    ActionState.SUCCEEDED,
    ActionState.FAILED_NO_CHANGE,
    ActionState.OUTCOME_UNKNOWN,
})

assert NON_TERMINAL_STATES | TERMINAL_STATES == set(ActionState)
assert not (NON_TERMINAL_STATES & TERMINAL_STATES)

#: The legal-transitions graph (contract "Legal transitions" diagram). A
#: terminal state has no outgoing edges -- no transition ever leaves one
#: (P10, "no transition out of a terminal state exists"; AC-6).
LEGAL_TRANSITIONS: dict[ActionState, frozenset[ActionState]] = {
    ActionState.CREATED: frozenset({
        ActionState.PREFLIGHTING,
        ActionState.ABORTED_PRE_MUTATION,
        ActionState.CANCELLED,
    }),
    ActionState.PREFLIGHTING: frozenset({
        ActionState.AWAITING_CONFIRMATION,
        ActionState.NOT_ELIGIBLE,
        ActionState.ABORTED_PRE_MUTATION,
        ActionState.CANCELLED,
    }),
    ActionState.AWAITING_CONFIRMATION: frozenset({
        ActionState.EXECUTING,
        ActionState.ABORTED_PRE_MUTATION,
        ActionState.CANCELLED,
    }),
    ActionState.EXECUTING: frozenset({
        ActionState.SUCCEEDED,
        ActionState.FAILED_NO_CHANGE,
        ActionState.OUTCOME_UNKNOWN,
    }),
    **{state: frozenset() for state in TERMINAL_STATES},
}


def is_legal_transition(from_state: ActionState, to_state: ActionState) -> bool:
    return to_state in LEGAL_TRANSITIONS.get(from_state, frozenset())


def is_terminal(state: ActionState) -> bool:
    return state in TERMINAL_STATES
