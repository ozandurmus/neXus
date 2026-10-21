# framework_mappings — Framework Mappings

## summary

Map technical evidence/findings to internal and external control frameworks. Designs: docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md. A signed / user-authored framework pack + a UI mapping editor (custom frameworks) is DEPLOY.1A-gated.

## why

Connects engineering evidence to audit and governance requirements - requirement by requirement, not just a framework-level percentage.

## early_foundation

CIS/PCI-DSS/BDDK control-area mappings and benchmark references delivered in 0.6.1B.1.5/1.6 as evidence-bounded area mappings. 0.7.1a added real per-control per-framework membership; 0.7.4 added the framework SIDE - a declarative requirement catalog (utils/framework_catalog.py) with requirement-level COVERED/PARTIALLY_COVERED/UNCOVERED/NOT_APPLICABLE + posture, version/profile, unmapped-ref drift guard, and a UI requirement drill-down.
