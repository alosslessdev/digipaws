import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "neth.iecal.curbox"
    compileSdk = 34
    flavorDimensions += "version"

    lint {
        disable.add("NullSafeMutableLiveData")
        abortOnError = false
    }

    defaultConfig {
        applicationId = "neth.iecal.curbox"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "Curbox")
    }

    productFlavors {
        create("fdroid") {
            dimension = "version"
            versionNameSuffix = "-fdroid"
            buildConfigField("Boolean", "FDROID_VARIANT", "true")
        }

        create("playstore") {
            dimension = "version"
            versionNameSuffix = "-playstore"
            buildConfigField("Boolean", "FDROID_VARIANT", "false")
        }
    }


    splits {
        abi {
            isEnable = false

        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Debug Curbox")
        }
    }

    // required because of hardcoded f-droid values
    applicationVariants.all {
        val variant = this
        if (variant.flavorName == "fdroid") {
            variant.outputs
                .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
                .forEach { output ->
                    val outputFileName = "app-fdroid-universal-release-unsigned.apk"
                    println("OutputFileName: $outputFileName")
                    output.outputFileName = outputFileName
                }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }
}



dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)


    // QR Scanner & Generator
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    
    // Shizuku dependecies
    implementation (libs.api)
    implementation (libs.provider)

    implementation(libs.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.mpandroidchart)
    implementation(libs.timerangepicker)

}
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        // This hooks into the standard "Run" action in Android Studio
        tasks.matching { it.name == "install$variantName" }.configureEach {
            doLast {
                val adbPath = sdkComponents.adb.get().asFile.absolutePath
                val appId = variant.applicationId.get()
                println(">>> Automatically wiping data for $appId")
                exec {
                    commandLine(adbPath, "shell", "pm", "clear", "--user", "0", appId)
                    isIgnoreExitValue = true
                }
            }
        }

        tasks.register("installAndGrantAccessibility$variantName") {
            group = "install"
            description = "Installs, wipes, grants permissions, and launches the app"
            dependsOn("install$variantName")
            
            doLast {
                val adbPath = sdkComponents.adb.get().asFile.absolutePath
                val appId = variant.applicationId.get()
                
                println(">>> Target Package: $appId")
                Thread.sleep(2000)

                // Wipe app data
                println(">>> Wiping data...")
                exec {
                    commandLine(adbPath, "shell", "pm", "clear", "--user", "0", appId)
                }

                // Grant Accessibility Permission
                println(">>> Granting permissions...")
                exec {
                    val baseId = "neth.iecal.curbox"
                    val combinedServices = "$appId/$baseId.services.AppBlockerService:$appId/$baseId.services.UsageTrackingService"

                    commandLine(adbPath, "shell", "settings", "put", "secure", "enabled_accessibility_services", combinedServices)
                }

                // Launch MainActivity
                println(">>> Launching app...")
                exec {
                    val baseId = "neth.iecal.curbox"
                    commandLine(adbPath, "shell", "am", "start", "-n", "$appId/$baseId.ui.activity.FragmentActivity")
                }
            }
        }
    }
}
