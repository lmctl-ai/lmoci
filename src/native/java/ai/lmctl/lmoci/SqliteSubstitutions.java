package ai.lmctl.lmoci;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jni.functions.JNIFunctionTables;
import com.oracle.svm.core.jni.headers.JNIJavaVM;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;

@Platforms(Platform.LINUX.class)
final class StaticSqliteEntrypoints {
    @CFunction("JNI_OnLoad_sqlitejdbc")
    static native int sqlite(JNIJavaVM vm, PointerBase reserved);

    static void initialize() {
        JNIJavaVM vm = JNIFunctionTables.singleton().getGlobalJavaVM();
        int version = sqlite(vm, WordFactory.nullPointer());
        if (version < 0) {
            throw new UnsatisfiedLinkError("bundled SQLite JNI initialization failed");
        }
    }
}

@Platforms(Platform.LINUX.class)
@TargetClass(className = "org.sqlite.SQLiteJDBCLoader")
final class SqliteSubstitutions {
    @Alias
    private static boolean extracted;

    @Substitute
    public static synchronized boolean initialize() {
        if (!extracted) {
            StaticSqliteEntrypoints.initialize();
            extracted = true;
        }
        return true;
    }
}
