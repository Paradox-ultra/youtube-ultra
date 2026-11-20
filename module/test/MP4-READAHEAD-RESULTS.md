# MP4 input readahead candidate, 2026-11-20

Status: cloud-tested candidate, not phone-tested. No APK was built or installed. Do not treat these results as proof that the reported phone saving delay is fixed.

Baseline: `5d06ac8098d245e5c3332e160cff090a6e437e2a`.

## Source findings and scope

The Android Q+ custom mux path already writes straight into MediaStore through a 1 MiB buffered stream. There is no separate completed-MP4 copy on that path. The existing custom muxer coalesces contiguous sample extents, but separated WebM block payloads still caused separate seeks/reads.

The candidate adds a bounded 256 KiB input window per source track, emits only the exact indexed sample bytes, retains the existing 1 MiB scratch path for large extents, and checks cancellation between bounded reads. Source length is cached once per reader. Files are assumed immutable while muxing. Reader-open failure closes the descriptor. Bounds, truncation and empty large-copy buffers are checked.

No codecs, sample metadata, interleaving, output layout, routing, account behavior or app identity changed. The normal native-first H.264 path, fallback policy and broader DownloadService progress behavior are unchanged. Cancellation during metadata parsing is not addressed.

## Executed cloud checks

On free CPU Kaggle and Colab sessions:

- Java 8-compatible full module and JVM-test compilation against Android SDK35 and repository-pinned libraries passed.
- Six existing JVM suites passed: AccountDownloadTest 37, FormatsTest 59, RoutesTest 48, HighResTest 34, V1012Test 21, V1013Test 46. Total245 checks.
- SampleReaderTest passed22 checks, including window reuse/refill, backward seeks, large samples, bounds, truncation, cancellation and synthetic full-MP4 byte parity.
- Existing muxer.sh passed66 checks for both baseline and candidate. It checks packet identity, decoding, durations, HDR, frame rates and box offsets for VP9, AV1, H.264 and AAC fixtures.
- Six complete output MP4 files were byte-identical between baseline and candidate on each service, including VP9 4K, VP9 1440p60, VP9 HDR, live-style WebM, AV1 fragmented MP4 and H.264 B-frames.

The first ad-hoc JVM runner placed android.jar before orgjson-test.jar and caused a Store-test failure. Correcting runtime classpath order to match module/test/run.sh removed it. No application change was made for that harness failure.

Colab subprocess output was not all visible in the notebook text export, but check=True completed every command, the final assertions and completion messages were present, and the cell completed successfully. Fixture generation can differ across service ffmpeg builds, so hashes are compared within each service, not across services.

## Performance observations, not a device-speed claim

Kaggle synthetic benchmark, 2,048 separated4 KiB samples in a16 MiB fixture,8 MiB emitted:

- Successful Java RandomAccessFile.read calls:2,048 baseline,64 candidate.
- Representative five-run median:7.785 ms baseline,3.983 ms candidate.

These are Java read calls, not physical storage I/O operations.

A separate12-second1080p WebM plus AAC case alternated baseline/candidate order, with one warmup and five measured runs each. All six complete MP4 pairs were byte-identical. Output:3,198,371 bytes, SHA256 `d5c1fa7f680b4763a94647f7dc65d318f47d8994e16124ee1e2a5953153ce554`.

- Baseline process seconds:0.109467,0.113995,0.110285,0.110173,0.100238. Median0.110173.
- Candidate process seconds:0.100429,0.098995,0.113667,0.108462,0.093316. Median0.100429.

The distributions overlap and include JVM startup, OS cache and output writes. This small fixture does not establish a useful saving-speed improvement on Android, large downloads, slow storage or MediaStore. Large-file phone measurements are still required. The native H.264 path is unoptimized.

## Reproduction

Run the normal `module/test/run.sh` with the pinned dependencies and Android35 SDK. It now includes SampleReaderTest. Run SampleReaderBenchmark separately with the same test classpath. `module/test/muxer-timing.py` requires ffmpeg, Java and BASELINE_CP/CANDIDATE_CP pointing to independently compiled baseline/candidate MuxCli classes. It reports process timing and asserts whole-output equality.

Next: when the laptop is available, build/sign with the existing signer, preserve installed app data, and measure real saving times on the affected phone before deciding whether to merge. Main and release assets are untouched.
