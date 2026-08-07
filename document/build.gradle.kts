plugins {
    id("rikkahub.android.library")
}

android {
    namespace = "me.rerere.document"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    // ML Kit text recognition (bundled, offline Chinese OCR for scanned PDFs)
    implementation(libs.mlkit.text.recognition.chinese)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
