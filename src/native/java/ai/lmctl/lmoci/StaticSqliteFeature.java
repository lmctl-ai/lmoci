package ai.lmctl.lmoci;

import com.oracle.svm.core.jdk.NativeLibrarySupport;
import com.oracle.svm.core.jdk.PlatformNativeLibrarySupport;
import com.oracle.svm.hosted.c.NativeLibraries;
import java.nio.file.Path;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.hosted.Feature;

/** Link SQLite JDBC native engine into the Linux executable instead of extracting shared libraries. */
public final class StaticSqliteFeature implements Feature {
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        if (!Platform.includedIn(Platform.LINUX.class)) return;
        var libraries = NativeLibraries.singleton();
        libraries.getLibraryPaths().add(Path.of("target/static-jni").toAbsolutePath().toString());
        NativeLibrarySupport.singleton().preregisterUninitializedBuiltinLibrary("sqlitejdbc");
        PlatformNativeLibrarySupport.singleton().addBuiltinPkgNativePrefix("org_sqlite_core_NativeDB");
        libraries.addStaticJniLibrary("sqlitejdbc");
    }
}
