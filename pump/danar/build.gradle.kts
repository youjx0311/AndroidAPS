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
    // 修正核心依赖配置（符合 Kotlin DSL 语法）
    implementation(project(":core:interfaces")) {
        exclude(group = "com.android.support") // 正确的 exclude 语法
        isTransitive = true // 依赖传递性配置
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
