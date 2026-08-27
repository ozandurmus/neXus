# START_HERE_CLAUDE.md

Claude ile tamamen Türkçe devam et.

## Her yeni session

1.  `CLAUDE.md` oku.
2.  `CURRENT_STATE.md` oku.
3.  Yalnız gerekli current `project/*` metadata'yı oku.
4.  Task için ilgili source/tests'i bul.
5.  Historical PHASE docs ve full Continuation Pack'i varsayılan olarak
    okuma.
6.  `data/`, `output/`, logs, CAS ve sensitive runtime artifact'lerini
    tarama.

## İlk cevap

Kod değiştirmeden önce kısa şekilde: - mevcut state, - task, - ihtiyaç
duyulan dosyalar, - risk, - bilerek yüklemediğin büyük context bildir.

## Normal implementation

Minimal change → targeted tests → gerektiğinde subsystem/full regression
→ build/diff summary → tek real-env validation command.

## Token hedefi

Repository proje hafızasıdır. Conversation history proje hafızası
değildir.

Task değişince yeni session açılabilir.

## Current checkpoint

B.1.2 direct-Clish collection real-environment PASS. Overall CP coverage
PARTIAL 101/122. Current engineering priority: DEV.1 Corporate Git Development Foundation. DEV.0.3A/B are complete; DEV.0.3C is deferred pre-server. Corporate Git remains blocked until the CP environment-specific exclusion identity default is externalized safely and the local gate passes. CP Device Interaction Safety remains P0 before recurring scheduling/concurrency increases.
