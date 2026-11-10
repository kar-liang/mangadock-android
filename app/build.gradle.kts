plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mangadock.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mangadock.android"
        // AGSL RuntimeShader 从 API 33 才有，液态玻璃跑不了更低的
        minSdk = 33
        targetSdk = 35
        // v73：版本号补齐（v38~v72 一直停在 2.0.37-android.1 / versionCode=1，
        // 「关于」里显示的版本号因此假死了 35 个版本）。v74~v80 按纪律递增。
        //
        // 命名规则照 v37 那版（`2.0.37-android.1`，commit a8c1f8c）：
        //   2.0.N        → 主版本跟内部迭代号 vN 走（v37 → 2.0.37，v80 → 2.0.80）
        //   -android.K   → 同一主版本下 Android 侧的第 K 个构建，换主版本时归 1
        //   versionCode  → 直接取 N，保证每出一版严格递增（历史包全是 1，无法区分，
        //                   从现在开始不许再忘）
        // ⚠️ 出包纪律：每次 assembleDebug / Release 出交付包前，这两行必须一起改。
        //    设置页「版本」直接读 BuildConfig.VERSION_NAME，别再手抄一遍。
        versionCode = 98
        versionName = "2.0.98-android.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 设置页「版本」直接读 BuildConfig.VERSION_NAME，别再手抄一遍版本号
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    // HttpURLConnection 有 HTTP 方法白名单，PROPFIND 直接抛 ProtocolException，
    // 所以 WebDAV 只能走 OkHttp（后面拉图片做 Range 请求也用它）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // 玻璃光学公式的 JVM 单测（sdRoundBox / 边缘权重 / 折射位移 / 模糊权重和）
    testImplementation("junit:junit:4.13.2")
}
