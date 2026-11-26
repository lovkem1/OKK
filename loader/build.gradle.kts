plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "io.github.lovkem1.okk.loader"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        // 定制版：允许自定义签名注入（默认 false，本定制版在 gradle.properties 打开）
        buildConfigField(
            "boolean",
            "ALLOW_CUSTOM_SIGNATURE",
            (project.findProperty("okk.allowCustomSignature")?.toString() ?: "false")
        )
        // 可选：精确可信签名 SHA256（留空则配合上面的开关放行任意自定义签名）
        buildConfigField(
            "String",
            "CUSTOM_SIGN_SHA256",
            "\"${project.findProperty("okk.customSignSha256")?.toString().orEmpty()}\""
        )
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":core"))

    // ── Compose 基座（对齐 wcx：Compose Multiplatform + MIUIX）──
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    // Dialog Compose 需要 ViewTreeLifecycleOwner / ViewTreeViewModelStoreOwner
    implementation("androidx.lifecycle:lifecycle-runtime-android:2.9.4")

    // MIUIX-KMP 0.9.3（对齐 wcx 视觉体系：毛玻璃/squircle/preference）
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-shader-android:0.9.3")

    // Material Symbols 图标（对齐 wcx 2.2.1）
    implementation("com.composables:icons-material-symbols-outlined-cmp:2.2.1")
    implementation("com.composables:icons-material-symbols-outlined-filled-cmp:2.2.1")

    // materialkolor 主题取色（对齐 wcx 5.0.0）
    implementation("com.materialkolor:material-kolor:5.0.0")
}
