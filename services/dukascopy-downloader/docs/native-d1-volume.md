# Native D1 volume

D1 is a Dukascopy server file, `BID_candles_day_1.bi5`. Its volume is copied from that file and kept as decoded. The downloader does not replace it with a sum of M1 or H1 volume.

M5, M15, M30, H1, and H4 still use their verified comparison rules. Those checks are unchanged.

D1 OHLC is a separate result, `d1_ohlc_verdict`. A price mismatch remains a failure.

D1 volume has two results:

- `d1_volume_source_integrity` is `PASS` when the decoded daily volume is present, finite, and not negative. A missing, malformed, non-finite, or negative daily volume is `FAIL`.
- `d1_volume_cross_timeframe_verdict` and `d1_volume_semantics` are `UNVERIFIED_SOURCE_SEMANTICS` when the source value is structurally valid. No verified formula equates that value to an M1 or H1 sum. An exact numerical match does not change this result. These two fields are never `PASS`.

The comparison report still records the date, the decoded native volume, the constituent sum, and the delta. Those residuals stay visible. They are not a tolerance and they are not a formula.

Readiness requires all of the following:

- M1 production integrity is `PASS`.
- M5, M15, M30, H1, and H4 reference validation and saved-file integrity are `PASS`.
- `d1_ohlc_verdict` is `PASS`.
- `d1_volume_source_integrity` is `PASS`.
- D1 cross-timeframe volume semantics remain `UNVERIFIED_SOURCE_SEMANTICS`.

`READY` is written only when that policy holds. The readiness report states the D1 limitation in the same terms. A D1 volume equivalence pass is not available.
