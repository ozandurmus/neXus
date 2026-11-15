# Diagnostic output mask hides sizes (289G), month abbreviations and ordinary words as SN- tokens; masked CP local-log output was unreadable (2026-10-31, 2026-11-02)

status: planned · target: 

2026-11-14 backlog truth: still open (P3, size M). Today: Diagnostic output for aiview partly unreadable; workaround exists. Evidence: Only workaround 5000bb6b (V183 numeric local-log reads, 11-03); SN- masking of sizes and words not fixed

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Commit 'Narrow diagnostic output masking to preserve sizes, calendar tokens, and dictionary words' (DiagnosticText, diagnostic-safe-words.txt, DiagnosticTextTest, DiagnosticServiceTest); later 'keep size ranges (64KB-128KB, 1.5GB-2GB, 512M-1G) readable' in logmask-v8. Not re-read against a real masked CP local-log output.
