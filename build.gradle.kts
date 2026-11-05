// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
// Frontend-owned files only: avoid reformatting StoryEngine/VoiceAgent in this integration patch.
val frontendKtlint by configurations.creating

dependencies {
    frontendKtlint("com.pinterest.ktlint:ktlint-cli:1.5.0") {
        attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL)) }
    }
}

val frontendPatterns = listOf(
    "app/src/main/java/com/roadmystery/app/vehicle/{MotionClassifier,MotionFlow,FakeMotionSignals,AaosMotionSignals,PleosVehicleState,SelectedVehicleState}.kt",
    "app/src/main/java/com/roadmystery/app/MainActivity.kt",
    "app/src/debug/java/com/roadmystery/app/vehicle/*.kt",
    "app/src/test/java/com/roadmystery/app/vehicle/*.kt",
)

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    classpath = frontendKtlint
    mainClass.set("com.pinterest.ktlint.Main")
    args(frontendPatterns)
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    classpath = frontendKtlint
    mainClass.set("com.pinterest.ktlint.Main")
    args(listOf("-F") + frontendPatterns)
}
