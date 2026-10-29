# WorkManager creates this worker reflectively after process restarts.
-keep class dev.notipush.ForwardWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }

# WorkManager's Room database is instantiated by a generated class name and
# getDeclaredConstructor(). R8 must retain its public no-argument constructor.
-keep class androidx.work.impl.WorkDatabase_Impl { public <init>(); }

# WorkManager also loads input mergers reflectively when executing queued work.
-keep class * extends androidx.work.InputMerger { public <init>(); }
