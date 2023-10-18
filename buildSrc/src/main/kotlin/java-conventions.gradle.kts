import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.java

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}