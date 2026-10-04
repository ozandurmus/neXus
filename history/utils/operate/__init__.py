"""OP.2.A / OP.2.B -- vendor-independent CLASS 2 execution foundation.

Frozen architecture authority:
``docs/history/phase/OP_2_0_CONTROLLED_HA_OPERATION_ARCHITECTURE.md``.

This package is the class 2 execution plane. Per that contract's P15 it is
deliberately empty of anything that could cause a device mutation until
``OP.2.C`` clears its own gate:

* no vendor adapter implementation (only the typed ``VendorCapabilityAdapter``
  boundary shape in ``adapter.py`` -- P11);
* no transport/collector module import;
* no command text anywhere, in any field, on any code path;
* no ``Authorizer`` that returns ``PERMIT`` outside ``tests/`` (P2 / AC-16);
* no argv/CLI entry point and no ``console/registry.py`` job type (P2, AC-12).

``tests/test_op2_a_b_execution_foundation.py`` carries the convergence
assertions that keep this true. ``utils/failover/`` is untouched -- this is
a new, separate package (P15), not an extension of it.

Class 2 (``utils.action_taxonomy.CLASS_2_OPERATIONAL_STATE_CHANGE``) stays
memberless: the only ``Authorizer`` wired anywhere outside tests is
``DenyAllAuthorizer``, which denies unconditionally, and no adapter exists
to resolve a capability even if it did not.
"""
from __future__ import annotations
