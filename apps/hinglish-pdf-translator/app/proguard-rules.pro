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

# Page structure is stored in Room as JSON via Gson, which reads field names
# reflectively: keep the stored model classes intact.
-keep class com.example.hinglishpdf.data.document.DocBlock { *; }
-keep enum com.example.hinglishpdf.data.document.BlockKind { *; }

# Gemini SDK: its request/response classes are (de)serialised with
# kotlinx.serialization; keep them so R8 cannot rename or strip them.
-keep class com.google.ai.client.generativeai.common.** { *; }
-keep class com.google.ai.client.generativeai.type.** { *; }
-dontwarn org.slf4j.**
