import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.*

// 顶层构建文件，配置所有子项目/模块的通用设置
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

    // 配置 Kotlin 编译选项（修正 JVM 目标版本类型）
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlin.ExperimentalUnsignedTypes",
                "-Xjvm-default=all"
            )
            // 关键修正：JvmTarget 需用枚举值，而非字符串
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // 配置 Java 编译选项（修正参数类型和集合写法）
    gradle.projectsEvaluated {
        tasks.withType<JavaCompile>().configureEach {
            options.apply {
                // 修正：addAll 接收集合参数
                compilerArgs.addAll(
                    listOf(
                        "-Xlint:deprecation",
                        "-Xlint:unchecked"
                    )
                )
                // 修正：sourceCompatibility/targetCompatibility 接收 String 类型的版本号
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
