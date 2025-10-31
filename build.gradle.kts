import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget  // 必须导入 JvmTarget

// 顶层构建文件
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.com.android.tools.build)
        classpath(libs.com.google.gms)
        classpath(libs.com.google.firebase.gradle)
        classpath(libs.kotlin.gradlePlugin)
        classpath(libs.kotlin.allopen)
        classpath(libs.kotlin.serialization)
    }
}

plugins {
    alias(libs.plugins.klint)
    alias(libs.plugins.moduleDependencyGraph)
    alias(libs.plugins.ksp)
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.google.com")
        maven("https://jitpack.io")
    }

    // 修正 Kotlin 编译配置（JvmTarget 枚举类型）
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlin.ExperimentalUnsignedTypes",
                "-Xjvm-default=all"
            )
            // 关键：使用 JvmTarget 枚举，而非字符串
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // 修正 Java 编译配置（参数类型和集合）
    gradle.projectsEvaluated {
        tasks.withType<JavaCompile>().configureEach {
            options.apply {
                // 修正：用 listOf 包装编译器参数（集合类型）
                compilerArgs.addAll(
                    listOf(
                        "-Xlint:deprecation",
                        "-Xlint:unchecked"
                    )
                )
                // 修正：使用字符串类型的 Java 版本
                sourceCompatibility = "21"
                targetCompatibility = "21"
            }
        }
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "jacoco")
}

apply(from = "jacoco_aggregation.gradle.kts")

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
