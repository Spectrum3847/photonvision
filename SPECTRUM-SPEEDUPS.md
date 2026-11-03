# SystemCore (CM5) speed-up ideas: MJPEG decode + NAPA

Ideas only; nothing here is implemented. Written 2026-11-03 from a review of `spectrum-2027`
(this fork) and SpectrumJetson (`detector/stream_decode.cc`, `detector/napa/`, `third_party/napa`
v0.5.1, `tests/napa-profile`, `tests/napa-stream`).

**Caveat:** the timings below were measured on an x86 Xeon (2.1 GHz, one pinned core), not on a CM5 or
Pi 5. Use them for relative cost only. Confirm on a SystemCore or Pi 5 with `LatencyTrace` and
`tests/napa-stream/run.sh timing`.

## Where the time goes

**Decode.** A 1280x800 frame (synthetic, 325 KB, 4:2:2, quality 85) decoded to gray with libjpeg-turbo:

| What | Time |
|---|---|
| Full decode to gray | ~3.26 ms |
| Decode scaled to 1/8 (mostly Huffman left) | ~2.27 ms |

- About **70% of the decode is Huffman** (entropy) decoding, which is serial. The rest is the inverse
  DCT plus writing the output.
- Streaming decode already hides the Huffman cost behind the USB transfer.

**NAPA** (`tests/napa-profile/results/profile.txt`):

- About **80% of NAPA's time is the scan**: sampling ("rake"), tile thresholding, dark-run extraction and
  union-find.
- The per-candidate stages (fit, decode, repairs) are small.

**How NAPA samples:** the first pass samples every `s`-th pixel of every `s`-th row, with
`s = min_tag_px*3/(4*W)`.

| Setting | Stride `s` | Pixels read |
|---|---|---|
| min tag 16 | 1 | all |
| base 32 (far search) | 2-3 | about 1/4-1/9 |

Edge fits and decodes read full-resolution pixels only around candidates.

**Constraint:** streaming NAPA is validated as bit-identical to whole-frame NAPA. Any idea that changes the
pixels NAPA sees changes its output, so it needs re-validation on `tests/napa-eval`.

## Ideas, in priority order

### 1. Remove the per-frame 1 MB copy and allocation (low effort, safe)
- `spectrum_stream_decode_gray` (`stream_decode.cc` ~line 720) copies every decoded frame row by row
  from the watcher's buffer into the Java Mat.
- `USBFrameProvider.grabMjpegAsGray` allocates a new 1280x800 `Mat` every frame. That means fresh pages
  each frame: page faults and zeroing.
- Fix: a small pool (about 3) of pre-allocated, pre-touched gray Mats. Either the decoder decodes straight
  into a pool buffer, or Java wraps the decoder's buffer, released back when the frame is done.
- Side benefits: `spectrum_stream_take` can always find the frame by pointer instead of the content-compare
  fallback ("the pipeline's Mat is a copy"), and there is less cache pollution before NAPA runs.
- Expected: about 0.1-0.3 ms a frame on the CM5 (estimate).

### 2. Remove the copies in the non-streaming CPU decode (low effort, safe)
- `CpuMjpegDecode.decode` copies the JPEG twice: native memory → Java `byte[]` (`toArray`), then →
  `MatOfByte`. That is ~300 KB twice, plus a Java heap allocation, every frame.
- Fix: wrap the native pointer as a direct `ByteBuffer` (`MemorySegment.asByteBuffer()`) and build the
  `Mat` with `new Mat(1, size, CV_8UC1, buffer)`, which doesn't copy.
- Better: call TurboJPEG (`tj3Decompress8`) to decode into a pooled buffer, which also avoids the new
  output `Mat` that `imdecode` allocates.
- The copies measured as small in C (within noise). The real benefit is less garbage collection pressure,
  which shows up in the p99.

### 3. Build for the A76 (trivial, safe)
- `scripts/jetson/07-build-napa-detector.sh` uses `-march=armv8-a+simd`. Use `-mcpu=cortex-a76` for the
  SystemCore (`-mcpu=cortex-a78ae` on the Orin).
- Build libjpeg-turbo 3.x the same way instead of using the distro or OpenCV-bundled copy. Its Huffman
  decoder is plain C and benefits from the newer version's fast path and tuning for the CPU.
- On the SystemCore, check that the libjpeg-turbo inside WPILib's OpenCV has NEON vector code:
  `Core.getBuildInformation()`.

### 4. NEON parity in NAPA's scan loops (medium, safe)
- `napa_core.c` has 6 SSE2 and 6 NEON blocks.
- Profile on ARM. Confirm that threshold + dark-run extraction (~45% of NAPA at min 16) and union-find
  have NEON paths as good as SSE2's. Look at `perf` on the CM5 for scalar hot loops.

### 5. Win back streaming NAPA's +28-30% CPU (medium, safe)
- Listed in `tests/napa-stream/README.md`: speculative skip of candidates a finer level will cover, and
  per-level buffer reuse.
- On a 4-core CM5 with several cameras, CPU is the limit, so this matters more than on the Orin.

### 6. Watcher polling (low effort, small)
- Each camera's watcher sleeps 0.1 ms when idle, about 10k wakeups a second per camera.
- Measured: feeding libjpeg-turbo in 4 KB chunks (about one 0.1 ms poll's worth) costs +3% decode CPU
  against one-shot; 1 KB chunks cost +20%. So don't poll faster.
- Option: sleep in step with the expected byte arrival rate, and sleep longer between frames (after EOI
  until near the next frame's expected start). That cuts wakeups without adding tail latency.

### 7. Fuse NAPA into the JPEG decoder (high effort, changes results)
- libjpeg-turbo's scaled inverse DCT (1/2, 1/4, 1/8 output) gives block averages. That is what NAPA's
  averaging rake (`box_rake`) computes for its coarse levels, but nearly free during decode, and with less
  noise.
  - The 1/8 scale is just the DC coefficients: a 160x100 thumbnail with no inverse DCT at all.
- The AC coefficients give a nearly free "flat block" mask during Huffman decode. NAPA already skips tiles
  without contrast, and this would skip them before any pixels exist.
- Lazy full resolution: keep the coefficients (or DC plus per-block bit offsets) and inverse-DCT
  full-resolution 8x8 blocks only around candidates and tracking crops.
  - Needs `jpeg_read_coefficients` plus vendored per-block inverse-DCT routines, which libjpeg-turbo
    doesn't expose publicly.
- Gains:
  - most of the ~30% non-Huffman decode cost;
  - NAPA's rake stage;
  - memory traffic.
- Mainly worth it with far search (base 32). At min tag 16 NAPA reads every pixel anyway.
- Cost: not bit-identical. Re-validate detections on `tests/napa-eval` and `same.py`.

### 8. Specialized Huffman decoder (high effort, guess 1.2-1.5x on the Huffman part)
- UVC cameras usually use the standard JPEG Huffman tables, or omit them, which means the standard ones.
- A decoder hard-coded for those tables, with a wide lookahead, that skips chroma values without fully
  decoding or storing them, could beat libjpeg-turbo's generic decoder. Fall back to libjpeg-turbo on any
  other table.
- This is the only idea that attacks the dominant serial cost.

### 9. Restart markers (check first)
- If a camera emits restart markers, each frame can be Huffman-decoded in parallel slices across cores.
- Check a captured frame from each camera model. Most UVC cameras don't emit them.

## Not worth it
- **Fast inverse DCT (`JDCT_IFAST` / `TJPARAM_FASTDCT`):** no gain measured, and it breaks identical pixels.
- **Fast-upsampling and colour-conversion flags:** these only affect colour output, and we decode to gray.
- **Hardware JPEG decode:** the BCM2712 (CM5) has none; the Pi 5 kept only HEVC decode.
- **Rust rewrite for speed:** the costs are Huffman decoding, memory traffic and NAPA's scan loops, which
  a language change doesn't move.
  - zune-jpeg is roughly on par with libjpeg-turbo and isn't built for streaming.
  - Rust is only worth it for a new standalone parser (idea 8), for memory safety on camera bytes.

## System settings (SystemCore)
- **CPU governor:** `performance`, so clock ramp-up doesn't add spikes to a ~3 ms task.
- **Core pinning:** one core per camera for the decoder/NAPA thread (`pinCameraCores`, as on the
  Jetson).
- **Isolated cores:** `isolcpus` / `nohz_full` for those cores, if possible.
- **Cache:** the A76 has 512 KB of L2 per core and 2 MB of shared L3. Band-by-band streaming keeps NAPA's
  working set in cache, an extra reason streaming should pay off on the CM5.

## How to measure
- **Device:** Pi 5 or the SystemCore. This cloud machine is x86, and qemu gives no meaningful timings.
- **Latency:** `LatencyTrace` in PhotonVision, and SpectrumJetson `tests/napa-stream/run.sh timing`.
- **Correctness:** `run.sh identity` plus `tests/napa-profile/same.py` for ideas 1-6 and 8, which must stay
  identical. `tests/napa-eval` for idea 7.
