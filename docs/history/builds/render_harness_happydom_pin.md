# render_harness_happydom_pin — Root-cause the render harness's happy-dom check failing under Bun

## Summary

The 2026-08-30 discovery note suspected a happy-dom >=20 API removal; real cross-runtime testing shows it is Bun's node:vm shim not implementing what happy-dom's per-Window script execution needs (window.eval/Function/Map/Error come back undefined under Bun on every happy-dom major 16-20, but work correctly under real Node with the same code and version). Fix: tests/test_html_render_harness.py now prefers node to run check-render.mjs, falling back to bun only if no Node is present; check-render.mjs itself is unchanged. An enableJavaScriptEvaluation-based rewrite was tried, found to break top-level scope leakage onto window, and reverted.
