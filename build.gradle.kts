import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
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

    // 配置 Kotlin 编译选项（统一 JVM 目标版本）
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlin.ExperimentalUnsignedTypes",
                "-Xjvm-default=all" // 支持 @JvmDefault 注解
            )
            jvmTarget.set("21") // 与 JDK 21 匹配
        }
    }

    // 配置 Java 编译选项（与 Kotlin 版本保持一致）
    gradle.projectsEvaluated {
        tasks.withType<JavaCompile>().configureEach {
            options.apply {
                compilerArgs.addAll(
                    "-Xlint:deprecation", // 显示 deprecation 警告
                    "-Xlint:unchecked"    // 显示 unchecked 警告
                )
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
    }

    // 应用通用插件
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "jacoco")
}

// 应用代码覆盖率报告聚合配置
apply(from = "jacoco_aggregation.gradle.kts")

// 注册 clean 任务（删除根项目构建目录）
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
