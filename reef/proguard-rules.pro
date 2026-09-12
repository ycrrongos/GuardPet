# Remove debug attributes and source file names
-keepattributes SourceFile, LineNumberTable

# Remove unnecessary annotations that aren't used at runtime
-keepattributes *Annotation*
-optimizationpasses 5
-overloadaggressively
-repackageclasses ''
-allowaccessmodification
