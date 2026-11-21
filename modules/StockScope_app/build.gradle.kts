plugins {
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    buildFeatures { buildConfig = true }
    namespace = "com.example.stockscope"
    compileSdk = 34
    defaultConfig { minSdk = 24; }
    buildFeatures { compose = true }
    
    kotlinOptions { jvmTarget = "17" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.activity:activity-compose:1.9.2")
}
