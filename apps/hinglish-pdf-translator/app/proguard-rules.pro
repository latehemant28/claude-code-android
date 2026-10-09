# MediaPipe calls back into Java from native code and uses protobuf-lite
# reflection; keep both intact under R8.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# PDFBox loads fonts/encodings reflectively, and references optional
# desktop-only classes (JPEG2000, AWT) that do not exist on Android.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.**
-dontwarn javax.annotation.**
