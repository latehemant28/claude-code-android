# Bundled model (optional)

Drop a MediaPipe-compatible model here (for example `gemma3-1b-it-int4.task`
or `Llama-3.2-3B-Instruct_multi-prefill-seq_q8_ekv1280.task`) to ship it inside
the APK. On first launch the app copies it to internal storage, because
MediaPipe needs a real file path.

Models are 0.5–3 GB, so bundling is mainly useful for internal builds. For
anything larger, use the in-app **Import model** button or `adb push` instead;
see the project README. `*.task` and `*.bin` files here are git-ignored.
