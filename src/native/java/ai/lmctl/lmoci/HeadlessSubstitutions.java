package ai.lmctl.lmoci;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className = "java.awt.Desktop")
final class HeadlessSubstitutions {
    @Substitute
    public static boolean isDesktopSupported() {
        return false;
    }
}
