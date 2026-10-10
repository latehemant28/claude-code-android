# Bundled model (optional)

`./gradlew assembleRelease -PembedModel` downloads Qwen 2.5 1.5B Instruct
(`Qwen2.5-1.5B-Instruct_q8_ekv1280.task`, ~1.6 GB, Apache-2.0) into
`app/build/qwenModel/assets/` at build time and packs it into the APK; plain
builds leave it out. A model placed in this folder by hand is always bundled. On first launch the app copies it to
internal storage, because MediaPipe needs a real file path.

Model files here are git-ignored. Without a bundled model, use the in-app
**Download Qwen** and **Import model** buttons, or `adb push`; see the
project README.
