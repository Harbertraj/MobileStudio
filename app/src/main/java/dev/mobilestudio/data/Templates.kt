package dev.mobilestudio.data

object Templates {

    /** Relative path to file content for a minimal single-activity Kotlin app. */
    fun emptyActivity(appName: String, packageName: String): Map<String, String> {
        val pkgPath = packageName.replace('.', '/')
        return linkedMapOf(
            "settings.gradle.kts" to """
                rootProject.name = "$appName"
                include(":app")
            """.trimIndent() + "\n",

            "app/build.gradle.kts" to """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                }

                android {
                    namespace = "$packageName"
                    compileSdk = 35
                    defaultConfig {
                        applicationId = "$packageName"
                        minSdk = 26
                        targetSdk = 35
                        versionCode = 1
                        versionName = "1.0"
                    }
                }
            """.trimIndent() + "\n",

            "app/src/main/AndroidManifest.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:label="@string/app_name">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent() + "\n",

            "app/src/main/java/$pkgPath/MainActivity.kt" to """
                package $packageName

                import android.app.Activity
                import android.os.Bundle
                import android.widget.TextView

                class MainActivity : Activity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(TextView(this).apply { text = "Hello from $appName" })
                    }
                }
            """.trimIndent() + "\n",

            "app/src/main/res/values/strings.xml" to """
                <resources>
                    <string name="app_name">$appName</string>
                </resources>
            """.trimIndent() + "\n",
        )
    }
}
