# Debug build is not minified; keep app + extractor classes.
-keep class app.pulse.** { *; }
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.annotation.**
