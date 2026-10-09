# Bundled model (optional)

Drop a model here (a LiteRT-LM `.litertlm` file, or a MediaPipe `.task` such as `gemma3-1b-it-int4.task`
) to ship it inside
the APK. On first launch the app copies it to internal storage, because
both runtimes need a real file path.

Models are 0.5–3 GB, so bundling is mainly useful for internal builds. For
anything larger, use the in-app **Import model** button or `adb push` instead;
see the project README. Model files here are git-ignored.
