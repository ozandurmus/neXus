# SarParser: one non-numeric sar CPU value looks like a header, so the row and block are silently dropped (disabled test names it)

status: automated_validated · target: 9c3579a7

2026-11-26: Fixed SarParser header detection (SarParser.classify/isKnownHeader/line) so a data row with >=2 non-numeric tokens is never mistaken for a header while unknown block headers still switch to Block.OTHER; enabled sarNonNumericValueSkipsTheWholeRowAndNeverBecomesZero and added five regression pins (:packet-analysis:test 440 passed, 0 skipped).
