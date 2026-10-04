from dataclasses import dataclass


@dataclass(frozen=True)
class RuntimeAuth:
    """In-memory authentication material for the current process only."""

    principal: str | None = None
    secret: str | None = None

    def __repr__(self):
        return "RuntimeAuth(<protected>)"
