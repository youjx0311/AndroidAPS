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
    // 其他 Android 配置（编译版本等）
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    defaultConfig {
        minSdk = 26
        targetSdk = 34
    }
}

dependencies {
    // 核心依赖：必须显式依赖 core:interfaces
    implementation(project(":core:interfaces")) {
        exclude(group = "com.android.support") // 排除冲突依赖
        isTransitive = true // 确保传递依赖有效
    }

    // 其他必要依赖
    implementation(project(":core:data"))
    implementation(project(":core:keys"))
    implementation(project(":core:objects"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))
    implementation(project(":core:validators"))
    implementation(project(":pump:dana"))

    api(libs.androidx.media3.common)

    // 测试依赖
    testImplementation(project(":shared:tests"))
    testImplementation(project(":core:objects"))

    // Dagger 相关（如果使用）
    ksp(libs.com.google.dagger.compiler)
    ksp(libs.com.google.dagger.android.processor)
}
