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

# The pipeline's placeholder tags and PDF text styles are stored the same way.
-keep class com.example.hinglishpdf.pipeline.segment.PlaceholderTag { *; }
-keep enum com.example.hinglishpdf.pipeline.segment.PlaceholderTag$Kind { *; }
-keep class com.example.hinglishpdf.pipeline.segment.TextStyle { *; }
