plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    buildFeatures { buildConfig = true }
    buildFeatures { buildConfig = true }
    namespace = "com.wordsaver"
    compileSdk = 34
    defaultConfig {
        minSdk = 24; 
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
