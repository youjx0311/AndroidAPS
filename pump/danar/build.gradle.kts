plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    id("kotlin-android")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.pump.danar"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces")) {
        // 修正 Kotlin DSL 语法：使用 exclude(mapOf(...))
        exclude(mapOf("group" to "com.android.support")
        isTransitive = true // Kotlin DSL 中使用 isTransitive
        version {
            strictly(project.version.toString()) // 明确转换为字符串
        }
    }
    implementation(project(":core:keys"))
    implementation(project(":core:objects"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))
    implementation(project(":core:validators"))
    implementation(project(":pump:dana"))

    api(libs.androidx.media3.common)

    testImplementation(project(":shared:tests"))
    testImplementation(project(":core:objects"))

    ksp(libs.com.google.dagger.compiler)
    ksp(libs.com.google.dagger.android.processor)
}
