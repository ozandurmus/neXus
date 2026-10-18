# Packet Analysis: hop markers "temiz" / "gecikme burada" (method proposal)

## Status

**APPROVED: Product Owner decision 2026-10-18** (`PO_DECISION_RECORD_2026_10_18_PACKET_ANALYSIS_HOP_VERDICT.md`): the
recommended rule (family C "breadth", the recommended values of section 7) and the wording of section 8. Implemented in
the expert report (`ui2/packet-analysis`, `export/HopVerdict`); the implementation notes, including the choices this
document left open, are in `ui2/packet-analysis/README.md` "Hop markers". The analysis below is unchanged from the
proposal.

*Original status (kept for the record):* DRAFT: pending Product Owner approval. Prepared 2026-10-16 on branch
`analysis/hop-thresholds` (from `rel/5-assembly` at `b0760aa6`, commit `c254d660`). This is an analysis and a proposal.
Approving this document is a *method* decision (which thresholds the product may use to put a word next to a hop); it is
not a code review.

## 1. The decision in plain words

The hand-made reference report put a word next to each hop of the chain: the hop is "temiz" (clean) or the delay is
"gecikme burada" (here). The automatic report has all the numbers behind such a word (how long packets take between two
neighbouring capture points before and after the event, how many were measured, how sure the clocks are) but it does not
say the word, because a word needs a threshold: *how much slower is "slower"?* Any threshold is a judgement. This
document gives the evidence for three ways of making that judgement, recommends one, and says what the word does and
does not mean. A threshold that is wrong in one direction hides a real delay behind "temiz"; in the other it accuses a
hop that is fine. The evidence below is chosen to show both kinds of error.

## 2. Recommendation in one screen

| | |
|---|---|
| **Rule** | Family C, "breadth": a hop is "gecikme burada" only if the average time grew by a clear amount **and** a real number of packets (not one or two spikes) were late. |
| **Values** | delay threshold **5 ms**; average at least **2 times** the earlier average; at least **10** measured packets in each window **and at least 10 late packets** (5 ms above the earlier median) whose share grew by at least **1 percentage point**; the growth must exceed **2 times the hop's clock uncertainty**; "temiz" only if the average moved by less than **1 ms** and the clocks could have shown a 5 ms change. Everything else: **belirsiz** (no marker). |
| **Wording** | `gecikme burada`, `temiz`; no marker for belirsiz, with the reason in the Hop table (section 8). |
| **Corpus (206 frozen cases)** | 21 of 24 injected delay hops marked "gecikme burada", **0 of 24 called "temiz"** (the 3 others stay belirsiz); **0 of 745** hops without an injected delay or loss marked "gecikme burada". Same numbers with the report's own default window and with a window set at the known start of the fault. |
| **Real cases (4 usable chains, 14 hops, no ground truth)** | 6 hops "temiz" in every window tried, 1 "gecikme burada" in every window, 2 "gecikme burada" in some windows, 1 belirsiz, 4 without data. 99.3% of hop/window verdicts do not change when any single value moves one step. |
| **Conservative** | 10 ms, 3x, 30 packets, 3x clock, 2% growth: fewer words, never a wrong one in the tests; marks fewer delays (14 of 24 with the default window, 19 with a known window). |
| **Sensitive** | 2 ms, 1.5x, 5 packets, 1x clock, 0.5% growth: finds 2 ms delays; puts "gecikme burada" on more loss-recovery hops (section 6.1). |
| **Biggest caveat** | "temiz" means *no increase in delay was measured*. It does not mean no loss. In the corpus 44 of 152 hops with injected loss, capture misses or blocked handshakes were called "temiz" because their delay really did not change. The reason line must show the match numbers next to the word (section 8). |

## 3. What the rule is given for each hop

A hop is a pair of neighbouring capture points. For each hop the report already computes, before and after the event
window (the engine's first onset, or a window the user gives):

- the **transit time** of the same packet seen at both points, forward and reverse: count, sum, maximum (so the mean);
- the **request/response components** "RX" (forward) and "TX" (reverse): the extra time the hop added to a matched cycle;
- the **matched-sample counts** per window, and the **clock uncertainty** of the pair (in ms).

The recommended rule needs two more numbers per window that the engine can compute exactly from the same rows: the
**median** and **how many samples are later than a given limit** (a count, not an estimate). Nothing else is new.

A static clock offset cancels when the same hop is compared before and after; what the clock uncertainty protects
against is a clock that moves between the two windows (drift, a step). The rule therefore asks for a growth larger than
a multiple of the uncertainty, and it refuses to say "temiz" where the uncertainty is so large that a delay of the
threshold size could not have been seen.

## 4. The three rule families

All families judge each signal of a hop (forward transit, reverse transit, RX, TX) that has enough samples in both
windows, and then combine them: any signal with a delay makes the hop "gecikme burada"; "temiz" needs every available
signal to be clean; otherwise belirsiz. Wording used below: X = delay threshold (ms); Y = ratio; N = minimum samples;
K = clock factor; Z = clean margin (ms); Bf = growth of the late-packet share.

**A, "mean" (the shape you proposed).** Delay: mean(after) - mean(before) >= X, mean(after)/mean(before) >= Y, N samples
in both windows, increase > K times the hop's clock uncertainty. Clean: the mean moved by less than Z and the clocks
could have shown X. Otherwise belirsiz.

**B, "relative".** Only the hop with the largest increase can get "gecikme burada", and only if its increase is at
least X and at least F times the second largest. Other hops: clean or belirsiz as in A. Idea: one problem, one marker.

**C, "robust".** Three variants were tested.
- *C-median*: A computed on the median of the per-packet times instead of the mean.
- *C-p95*: A computed on the 95th percentile.
- *C-breadth* (recommended): A on the mean, **plus** the delay must be carried by many packets: at least N packets
  must be X ms above the earlier median and their share must have grown by at least Bf; "temiz" additionally needs the
  share of packets Z ms above the earlier median to have grown by less than Bf. This keeps the mean (which sees
  intermittent delay) but refuses to be moved by one or two spikes (which are often matching artefacts, not delay).

## 5. How it was tested, and how far the evidence goes

1. **The 206 frozen corpus cases** (`packet-analysis-parity-corpus.tar.gz`, SHA-256 `00f5957f...b42a`) were run
   end to end through the Java engine (`PacketAnalysisMain`): 158 MATCH and 48 RECORDED-TIES against the frozen
   reference, none different. Each case's *injected fault* comes from the generator's ground truth and the
   generator code (`PacketBreaker/src/packetbreaker/*_synthetic.py`, read only).
2. **Ground truth per hop.** 926 hops (adjacent point pairs) in 203 cases: **24 hops with an injected delay** (5
   families: `delay`, `onset_delay`, `onset_propagation`, `pilot`, `real_case_2`), **152 hops with injected loss,
   capture misses, blocked handshakes** (not delay), **5 hops with a constant jitter from the start**
   (`udp_transactions`: no before/after change, so no marker is expected; reported separately), and **745 hops with
   nothing injected**. The three `*_replica` cases (15 hops) mimic real incidents and have no ground truth.
3. **Two windows per case.** *Default*: the window the report would pick itself (the engine's first onset; where
   it finds none, no comparison exists and the hop gets no word: 99 of the 206 cases). *Known start*: a window split
   at the known start of the fault (the middle of the capture where nothing was injected). All 24 delay cases have an
   engine onset, so the default window always works for them.
4. **A graded delay sweep** I generated with the frozen generator: injected delay 0.5, 1, 2, 3, 5, 10, 20, 50 ms at
   each of the 4 hops of the 5-point chain (32 cases), to find the smallest delay each rule can mark.
5. **Stress tests on real and corpus signals**: add one to five huge outlier packets; add a clock step.
6. **The 6 anonymised real cases** (no ground truth). Only 4 chains could be built: cases 1 and 2 are slices of
   captures that do not overlap in time (0 capture pairs share even 50 packets) and case 6 has one pair sharing 134
   packets. For cases 3, 4 and 5 I built the chain order **myself** from TTL steps and timing (the engine itself
   calls these topologies ambiguous), case 4 as two separate paths. The product's window guard limited each analysis
   to a 10-second window, and the before/after split was tried at the engine's onset (where found) and at 25%, 50%
   and 75% of that window. Only counts are reported; no address or name was read or is written here.

**What this evidence cannot do.**
- The corpus is synthetic and noise free: clean hops show exactly the same transit time before and after, so "0 false
  alarms" there is weak evidence on its own. The false-alarm side rests on the stress tests and on the real cases.
- The 24 delay hops are 5 families, so "21 of 24" is really "4 of 5 families" (the fifth, `real_case_2`, is a host
  holding packets for a fraction of the cycles and shows as retransmissions, not as slower transit).
- The injected delays are large (25 to 110 ms mean). Small delays are only covered by the sweep.
- The corpus clock uncertainty is artificially high (median 3.0 ms per hop, growing 2 ms per hop along the chain);
  the real cases show 0.001 to 1.1 ms (0.04 to 0.19 ms on the hops of real cases 4 and 5, up to 1.1 ms in case 3). The clock rule therefore blocks many corpus hops and almost none of the real ones.
- Real cases: 14 hops in 4 chains, order assumed by me, no ground truth. They show how the rules *behave* on noisy data,
  not whether they are right.

## 6. Results

### 6.1 The three families on the corpus (same base values: 5 ms, 2x, 10 packets, 2x clock, 1 ms)

"Delay hops" are the 24 hops with an injected delay. "Window known" = split at the true start; "default" = the
report's own window.

| Rule | Delay hops marked "gecikme burada" (known / default) | Delay hops called "temiz" (the dangerous error) | Hops without delay called "gecikme burada" (of 745) | Loss/miss hops marked "gecikme burada" (of 152) | Hops without delay called "temiz" (known / default) |
|---|---|---|---|---|---|
| **C-breadth (recommended)** | **21 / 21** | **0** | **0** | 0 / 15 | 336 / 209 |
| A, mean | 21 / 21 | 0 | 0 | 7 / 16 | 336 / 209 |
| B, relative | 21 / 16 | 0 | 0 | 7 / 11 | 336 / 209 |
| C-p95 | 20 / 20 | 4 | 0 | 7 / 26 | 336 / 209 |
| C-median | 16 / 16 | **8** | 0 | 0 / 0 | 336 / 209 |

How to read it.
- **Median-only is unsafe**: it called 8 of the 24 delayed hops "temiz" (the `pilot` and `real_case_2` families, where
  only some packets are held). The 95th percentile alone still calls 4 "temiz".
- **B (one marker only)** loses 5 delay hops with the default window: in `onset_propagation` a loss hop and a delay hop
  rise together, so neither "stands out".
- **A and C-breadth agree on the corpus**; they differ where the corpus has no cases: one or two spikes (section 6.4).
- The "gecikme burada" on loss/miss hops is a delay marker at a hop where packets were lost and recovered by
  retransmission (the request took longer there). The location is right; the cause is loss, not path delay. This is
  why a marker is never a root cause (section 9).
- Why only 336 of 745 clean hops get "temiz" (the rest are belirsiz): in the *known* window, 374 hops are blocked by the
  clock rule (the corpus uncertainty is high) and 35 have too few packets. In the *default* window, 365 clean hops have
  no before/after comparison at all because the engine found no onset.
- The 3 delay hops the recommended rule leaves belirsiz are `real_case_2` variants with +0.7 ms on 0.4% of packets: the
  words "temiz" would be wrong there, which is why the clean margin Z must stay at 1 ms (Z = 2 ms calls all 3 "temiz").

### 6.2 Do small changes in the values change the words? (recommended rule)

Each row moves one value one step on the grid and shows the corpus result (known window / default window); the base is
21/24 delay hops marked, 0 wrong "temiz", 0 false "gecikme burada".

| Value moved | Delay hops marked | Delay hops "temiz" | False "gecikme burada" | Comment |
|---|---|---|---|---|
| 5 -> 3 ms | 21 / 21 | 0 | 0 | 54 more belirsiz instead of "temiz" |
| 5 -> 10 ms | 20 / 20 | 0 | 0 | loses the `real_case_2` hop; more "temiz" |
| ratio 2 -> 1.5 or 3 | 21 / 21 | 0 | 0 | no effect: the ratio is a safety net only |
| samples 10 -> 5 or 20 | 21 / 21 | 0 | 0 | no effect |
| clock factor 2 -> 1 | 21 / 21 | 0 | 0 | more "temiz" (541 instead of 336) in the noisy-clock corpus |
| clock factor 2 -> 3 | 21 / 16 | 0 | 0 | loses `onset_propagation` with the default window |
| clean margin 1 -> 0.5 | 21 / 21 | 0 | 0 | no effect |
| clean margin 1 -> **2** | 21 / 21 | **3** | 0 | **unsafe**: 3 delayed hops called "temiz" |
| growth 1% -> 0.5% | 21 / 21 | 0 | 0 | no effect |
| growth 1% -> 2% | 20 / 20 | 0 | 0 | loses the `real_case_2` hop (1.4% of packets late) |

The plateau is wide in X (3 to 5), the ratio, the sample count and the growth; it has hard edges only at the clean
margin (must be 1 ms or less) and at the clock factor (3 is too strict).

### 6.3 Smallest delay each setting marks (sweep: injected delay on one hop of a 5-point chain)

The hop clock uncertainty in this chain grows 1, 3, 5, 7 ms along the path (corpus artefact). Letters: D = "gecikme
burada", C = "temiz", U = belirsiz. None of the 96 untouched hops in any row was ever marked D.

Recommended (5 ms, 2x clock):

| Injected delay | hop with 1 ms clock uncertainty | 3 ms | 5 ms | 7 ms |
|---|---|---|---|---|
| 0.5 ms | C | U | U | U |
| 1 to 3 ms | U | U | U | U |
| 5 ms | D | U | U | U |
| 10 ms | D | D | U | U |
| 20 ms and 50 ms | D | D | D | D |

Sensitive (2 ms, 1x clock): 2 to 3 ms marked at 1 ms uncertainty, 5 ms at 3 ms, 10 ms everywhere. Conservative
(10 ms, 3x clock): 10 ms at 1 and 3 ms uncertainty, 20 ms at 5 ms, 50 ms everywhere. In every setting a delay below the
threshold stays belirsiz (never "temiz") except the case where it is below the clean margin (0.5 ms), which is what a
1 ms clean margin means. With the real cases' uncertainty (0.001 to 1.1 ms) the recommended rule behaves like the first
column.

### 6.4 Stress tests (recommended vs the plain mean rule A, clock rule switched off so only the packet rule is tested)

Added to the "after" window of 1,306 clean signals (1,280 corpus, 26 real):

| Added to the after window | A (mean) turns the hop into "gecikme burada" | C-breadth (recommended) |
|---|---|---|
| 1 packet 100 ms late | 0.2% | 0% |
| 1 packet 500 ms late | **33%** | 0% |
| 2 packets 500 ms late | **56%** | 0% |
| 3 packets 2000 ms late | **85%** | 0.1% |
| 5 packets 500 ms late | **73%** | 0% |

So a plain mean rule gives a delay accusation from a few stray packets (a mismatched retransmission is enough). The
breadth condition removes that. The opposite risk: a *clock step* looks exactly like delay. A 2 ms or smaller step never
triggers the rule; a 10 ms step triggers it on every hop with clock factor 0 or 1 and on 75% with factor 2. The clock
rule cannot tell a step from a delay; that limit is stated in section 9.

### 6.5 The real cases (no ground truth; how the rules behave)

Four chains (case 3; case 4 as two paths; case 5), 14 hops, 3 to 9 capture points each, windows: engine onset where found
(cases 4 and 5) and splits at 25%, 50%, 75% of the analysed 10 seconds (case 3 had no engine onset, so only the three
splits). Counts are hops, by what the rule said across the windows tried:

| Rule | "temiz" in every window | "gecikme burada" in every window | "gecikme burada" in some windows | belirsiz | no data | Verdicts unchanged when one value moves a step |
|---|---|---|---|---|---|---|
| **C-breadth, recommended** | 6 | 1 | 2 | 1 | 4 | 99.3% |
| Conservative | 6 | 0 | 1 | 3 | 4 | 98.5% |
| Sensitive | 6 | 1 | 2 | 1 | 4 | 99.5% |
| A, mean | 6 | 1 | 2 | 1 | 4 | 99.4% |
| B, relative | 6 | 1 | 2 | 1 | 4 | 99.2% |
| C-p95 | 6 | 0 | 3 | 1 | 4 | 99.8% |
| **C-median** | **10** | **0** | **0** | 0 | 4 | 100% |

What the real hops looked like (numbers only):
- One hop had its mean transit rise from 0.3 to 31.6 ms while the **median did not move** (0.03 to 0.04 ms): in the
  window after the event 11.9% of 18,746 packets (2,229) were 5 ms or more later than before (0.1% before). The
  recommended rule says "gecikme burada" in all four windows. **Median-only says "temiz" for it**, and for every
  other delayed-looking hop: it never produced a single delay word on real data.
- A second hop had 4.0% of 1,212 packets (48) 5 ms later after the event than before (0.3%); the word appears in the
  two windows that contain the event and disappears in the two later splits where the event is already over. The
  marker depends on the window, so every marker is printed with its window.
- One hop was slow *before* the window and recovered (mean 22 ms to 3.8 ms): belirsiz, because "temiz" would hide that
  it had been slow, and a delay word would be wrong.
- Four hops had no usable matched packets in the analysed 10 s window: no word.

### 6.6 What I tried and dropped

- **Loss guard on "temiz"** (do not say "temiz" if the hop has dropped or missing matches): it halved the loss-hop
  "temiz" count in the corpus (44 to 19) but also halved all "temiz" (336 to 187), and on the real cases unmatched shares
  of 46% to 99% occur on hops that are fine (the points see different traffic). It would remove every real "temiz".
  Not recommended as an automatic rule; the match numbers are shown in the reason line instead.
- **A ratio condition** has no effect in any test; it stays only because it makes the sentence "2 times slower" true.

## 7. Recommendation and the two alternatives

| Value | Recommended | Conservative | Sensitive |
|---|---|---|---|
| Delay threshold X (ms) | 5 | 10 | 2 |
| Mean at least (times the earlier mean) | 2 | 3 | 1.5 |
| Samples in each window, and late packets needed | 10 | 30 | 5 |
| Growth of late-packet share (points) | 1% | 2% | 0.5% |
| Increase must exceed (times hop clock uncertainty) | 2 | 3 | 1 |
| Clean margin Z (ms) | 1 | 1 | 0.5 |
| Corpus: delay hops marked (known / default window) | 21 / 21 of 24 | 19 / 14 | 21 / 21 |
| Corpus: delay hops called "temiz" | 0 | 0 | 0 |
| Corpus: false "gecikme burada" (of 745) | 0 | 0 | 0 |
| Corpus: loss hops marked "gecikme burada" (of 152) | 0 / 15 | 0 / 0 | 16 / 26 |
| Smallest sweep delay marked (clock uncertainty 1 ms) | 5 ms | 10 ms | 2 ms |
| Real hops: "gecikme burada" in every / some windows | 1 / 2 | 0 / 1 | 1 / 2 |

**Why the recommended one.** It is the only family that is safe on all three kinds of evidence: it never calls a delayed
hop "temiz" (median-only does, 8 of 24), it is not moved by a few stray packets (the plain mean is, 33% to 85% of the time),
and it still sees intermittent delay carried by 1% to 12% of packets (the real incident shape, which the 95th percentile
alone partly misses and the median misses entirely). X = 5 ms is where the corpus plateau and the real-case behaviour are
both stable; a 5 ms change between two capture points is also material for a hop in a data centre or campus path. The
clock factor 2 is your proposal; in the real cases it never binds (uncertainty 0.001 to 1.1 ms against a 5 ms threshold),
it only binds in the high-uncertainty synthetic corpus, where it turns questionable hops into belirsiz.

**Use the conservative set** if a wrong accusation is costlier than a missed delay (it never produced one in any test,
and with the default window it marks only 14 of the 24 corpus delay hops and 1 of the 3 real "gecikme burada" hops).
**Use the sensitive set** for LAN-scale paths where 2 ms matters, accepting more "gecikme burada" on hops where loss was
recovered by retransmission.

## 8. Exact wording

Two visible words, one per hop (adjacent capture-point pair), printed under the hop arrow of the chain strip in the
Summary sheet (a hop inside one device, a "cihaz içi" hop, in that device's box) and as a "Karar" column plus a
"Gerekçe" column in the Hop Analysis sheet:

- **`gecikme burada`**
- **`temiz`**
- belirsiz: no word on the strip; the Hop table says `belirsiz` and why.
- no measurement at all: `ölçüm yok`, no word on the strip.

Every marker is printed with the window it was computed for (`Pencere: önce 12,0 sn / sonra 28,0 sn`), because a
marker can change when the window moves (one real hop did).

**Reason lines.** Numbers only, fixed templates, Turkish decimal comma (`TrFormat`). The numbers are the same ones
the rule used; the sentence never explains a cause.

`gecikme burada`:

> gecikme burada (ileri yön geçiş süresi): ortalama 0,29 → 31,59 ms (+31,30 ms, 109×); önceki ortancadan 5 ms fazla geç
> kalan paketler önce %0,1, sonra %11,9 (2.229 paket); örnek sayısı 71.228 / 18.746; hop saat belirsizliği ±0,13 ms.

`temiz`:

> temiz (ileri ve geri yön geçiş süresi): ortalama fark 0,00 ms (sınır ±1 ms); geç kalan paket payı artmadı; örnek
> sayısı 70.856 / 18.625; eşleşmeyen gözlem önce %0,6, sonra %0,1 (bu oran tek başına kayıp anlamına gelmez).

`belirsiz` (the first matching line is used; `ölçüm yok` when a hop has no matched packet in either window; the numbers in lines 2 to 7 are illustrative):

1. `belirsiz: önce/sonra karşılaştırması yok (pencere verilmedi, motor olay başlangıcı bulamadı).`
2. `belirsiz: örnek yetersiz; önce 7, sonra 4 (en az 10 gerekir).`
3. `belirsiz: saat belirsizliği ±3,0 ms; 2× = 6,0 ms, 5 ms'lik bir değişim bu hop'ta ölçülemez.`
4. `belirsiz: ortalama +2,4 ms değişti; temiz sınırının (1 ms) üstünde, gecikme eşiğinin (5 ms) altında.`
5. `belirsiz: ortalama +7,5 ms arttı ama yalnız 3 paket (%0,2) geç kaldı; en az 10 paket ve payın %1 artması gerekir.`
   (the share and count in this line are the ones the rule actually tested, so a reader can check them)
6. `belirsiz: ortalama -18,5 ms azaldı; önceki pencerede gecikme vardı.`
7. `belirsiz: artış +7,0 ms, hop saat belirsizliğinin 2 katından (8,0 ms) küçük.`

**Sentences that change in the workbook** (new text, replacing statements that today say the report has no verdict):

- Methods sheet, thresholds table (new): the six values above, with the rule in one sentence:
  `"gecikme burada" = ortalama geçiş süresi en az 5 ms ve 2 kat arttı, artış hop'un saat belirsizliğinin 2 katından büyük,
  en az 10 paket önceki ortancanın 5 ms üstüne çıktı ve bu paketlerin payı en az 1 puan arttı. "temiz" = ortalama fark ±1
  ms içinde, geç kalan paket payı artmadı ve ölçüm 5 ms'lik bir değişimi görebilecek hassasiyette. Diğer her durum belirsizdir.`
- Limitations list: replace `Rakamlar sayı ve ortalamalardır; rapor bir hüküm veya sınıflandırma içermez.` with
  `Hop işaretleri (temiz / gecikme burada) yalnız Yöntem sayfasındaki sabit eşiklerle üretilir; kök neden, kayıp veya
  yeniden iletim hükmü değildir.`

## 9. What is explicitly NOT claimed

- A marker is **not a root cause**. "gecikme burada" means: between these two capture points, packets took more time in
  the compared window than in the earlier one. It covers everything between the points (device processing, queues,
  the link, the capture point itself). It does not say why, and a recovered loss can produce it (the retransmission
  made the request slower) with no path delay at all.
- **"temiz" is about delay only.** It does not say no packet was lost, blocked or missed: in the corpus 44 of 152 hops with
  injected loss, capture misses or a blocked handshake were "temiz" because their delay did not change. Loss and match
  figures are on the same row and must be read with the word.
- Nothing is said about a hop whose packets cannot be matched, whose window is missing, or that has too few packets:
  those get no word.
- A **clock step** (an NTP jump, a VM pause) looks like delay and cannot be separated by the clock-uncertainty rule;
  steps of 2 ms or less never trigger it, 10 ms steps do. The uncertainty is the engine's estimate under the minimum
  path-symmetry assumption; it is not a measurement of the real clock error.
- **Capture limits:** SPAN drops or oversubscription, the unknown place where a timestamp is taken (NIC or kernel),
  truncated packets, offload super-frames and traffic that one point sees and the next does not are not modelled;
  the markers inherit all of them.
- The marker depends on the **before/after window**. The engine's own onset is one choice; a user window is another;
  a delay that was always there (constant jitter, the five `udp_transactions` hops) produces no before/after change and
  therefore no marker at all.
- The thresholds were set on synthetic captures with large, clean injected delays and checked, not validated, on four real
  chains with no ground truth. They are a starting point to be revised when labelled real incidents exist.

## 10. What approval would unlock (not done here)

Size: small to medium, one lane. Work: (1) `ExpertReportBuilder`: per window median and the count of samples above
a limit, as exact integer queries so output stays byte-identical across threads (`DETERMINISM.md`); (2) a pure verdict
class with the six values as constants and the seven belirsiz reasons; (3) `ExpertWorkbook`: marker row under the chain
arrows, "Karar"/"Gerekçe" columns, thresholds table, the two reworded sentences; (4) tests: fixture with a step delay,
a spike, a clock-noise-only hop, a median-flat tail delay; the existing workbook golden tests will change by design;
(5) text changes in `ExpertFindings` ("no threshold and no verdict") and the builder comment. Risk: the report stops being
"numbers only", so the markers need the `Yöntem` sheet text above and this document as the authority.

## 11. Decisions requested from the Product Owner

1. Approve the rule family and values (recommended, conservative or sensitive).
2. Approve the two words and the meaning of "temiz" as *no measured increase in delay*, shown with the loss/match numbers.
3. Approve "no word" for belirsiz and for "ölçüm yok", with the reason in the Hop table.
4. Decide what happens when the engine finds no onset (99 of the 206 corpus cases, and real case 3): no markers (as
   proposed), or ask the user to give a window.
5. Optionally commission a labelled calibration set (graded delays of 1 to 50 ms on the real topology, with noise and
   jitter) so the values can be validated rather than checked.

## Appendix: what was run

- Engine: this branch (`b0760aa6`); corpus archive `~/Codo/backups/packet-analysis-parity-corpus.tar.gz`
  (SHA-256 matches `manifest.json`); generator and sweep from the frozen PacketBreaker checkout `5b608e6` (read only).
- Inputs per hop were read from the same code the report uses (`ExpertReportBuilder`, via a throw-away dumper that
  was not committed); per-packet statistics (median, late-packet counts) were computed from the case databases with the
  same period definitions. Product means were cross-checked against the per-packet means.
- Grid: 6 values of X, 4 of ratio, 4 of samples, 4 of clock factor, 4 of clean margin, 4 of growth, 4 of F for rule B;
  about 10,000 settings x 2 windows x 926 hops. Neighbours: one grid step per value.
- Scratch (unpacked corpus, case databases, real-case databases) was deleted after the run; the scripts and result
  files are small and were kept outside the repository.
