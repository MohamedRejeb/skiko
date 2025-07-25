package tasks.configuration

import Arch
import OS
import SkiaBuildType
import SkikoProperties
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.kotlin.dsl.get
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool
import registerSkikoTask
import supportAndroid
import supportWasm
import toTitleCase
import java.io.File
import skiaVersion
import supportNativeLinux

fun skiaHeadersDirs(skiaDir: File): List<File> =
    listOf(
        skiaDir,
        skiaDir.resolve("include"),
        skiaDir.resolve("include/core"),
        skiaDir.resolve("include/gpu"),
        skiaDir.resolve("include/effects"),
        skiaDir.resolve("include/pathops"),
        skiaDir.resolve("include/utils"),
        skiaDir.resolve("include/codec"),
        skiaDir.resolve("include/svg"),
        skiaDir.resolve("modules/jsonreader"),
        skiaDir.resolve("modules/skottie/include"),
        skiaDir.resolve("modules/skparagraph/include"),
        skiaDir.resolve("modules/skshaper/include"),
        skiaDir.resolve("modules/skunicode/include"),
        skiaDir.resolve("modules/sksg/include"),
        skiaDir.resolve("modules/svg/include"),
        skiaDir.resolve("third_party/externals/harfbuzz/src"),
        skiaDir.resolve("third_party/icu"),
        skiaDir.resolve("third_party/externals/icu/source/common"),
    )

fun includeHeadersFlags(headersDirs: List<File>) =
    headersDirs.map { "-I${it.absolutePath}" }.toTypedArray()

fun skiaPreprocessorFlags(os: OS, buildType: SkiaBuildType): Array<String> {
    val base = listOf(
        "-DSK_ALLOW_STATIC_GLOBAL_INITIALIZERS=1",
        "-DSK_FORCE_DISTANCE_FIELD_TEXT=0",
        "-DSK_GAMMA_APPLY_TO_A8",
        "-DSK_GAMMA_SRGB",
        "-DSK_SCALAR_TO_FLOAT_EXCLUDED",
        "-DSK_SUPPORT_GPU=1",
        "-DSK_GANESH",
        "-DSK_GL",
        "-DSK_SHAPER_HARFBUZZ_AVAILABLE",
        "-DSK_UNICODE_AVAILABLE",
        "-DSK_SHAPER_UNICODE_AVAILABLE",
        "-DSK_SUPPORT_OPENCL=0",
        "-DSK_UNICODE_AVAILABLE",
        "-DU_DISABLE_RENAMING",
        "-DSK_USING_THIRD_PARTY_ICU",
        // For ICU symbols renaming:
        "-DU_DISABLE_RENAMING=0",
        "-DU_DISABLE_VERSION_SUFFIX=1",
        "-DU_HAVE_LIB_SUFFIX=1",
        "-DU_LIB_SUFFIX_C_NAME=_skiko",
        *buildType.flags
    )

    val perOs = when (os) {
        OS.MacOS -> listOf(
            "-DSK_SHAPER_CORETEXT_AVAILABLE",
            "-DSK_BUILD_FOR_MAC",
            "-DSK_METAL"
        )
        OS.IOS -> listOf(
            "-DSK_BUILD_FOR_IOS",
            "-DSK_SHAPER_CORETEXT_AVAILABLE",
            "-DSK_METAL"
        )
        OS.TVOS -> listOf(
            "-DSK_BUILD_FOR_IOS",
            "-DSK_BUILD_FOR_TVOS",
            "-DSK_SHAPER_CORETEXT_AVAILABLE",
            "-DSK_METAL"
        )
        OS.Windows -> listOf(
            "-DSK_BUILD_FOR_WIN",
            "-D_CRT_SECURE_NO_WARNINGS",
            "-D_HAS_EXCEPTIONS=0",
            "-DWIN32_LEAN_AND_MEAN",
            "-DNOMINMAX",
            "-DSK_GAMMA_APPLY_TO_A8",
            "-DSK_DIRECT3D"
        )
        OS.Linux -> listOf(
            "-DSK_BUILD_FOR_LINUX",
            "-D_GLIBCXX_USE_CXX11_ABI=0"
        )
        OS.Wasm -> listOf(
            "-DSKIKO_WASM",
            "-sSUPPORT_LONGJMP=wasm"
        )
        OS.Android -> listOf(
            "-DSK_BUILD_FOR_ANDROID"
        )
        else -> TODO("unsupported $os")
    }

    return (base + perOs).toTypedArray()
}

fun Project.configureSignAndPublishDependencies() {
    if (supportWasm) {
        tasks.configureEach {
            val publishJs = "publishJsPublicationTo"
            val publishWasm = "publishSkikoWasmRuntimePublicationTo"
            val publishWasmPub = "publishWasmJsPublicationTo"
            val signWasm = "signSkikoWasmRuntimePublication"
            val signJs = "signJsPublication"
            val signWasmPub = "signWasmJsPublication"

            when {
                name.startsWith(publishJs) -> dependsOn(signWasm, signWasmPub)
                name.startsWith(publishWasm) -> dependsOn(signJs)
                name.startsWith(publishWasmPub) -> dependsOn(signJs)
                name.startsWith(signWasmPub) -> dependsOn(signWasm)
            }
        }
    }
    if (supportAndroid) {
        tasks.configureEach {
            val signAndroid = "signAndroidReleasePublication"
            val generateMetadata = "generateMetadataFileForAndroidReleasePublication"
            val publishAndroid = "publishAndroidReleasePublicationTo"
            val publishX64 = "publishSkikoJvmRuntimeAndroidX64PublicationTo"
            val publishArm64 = "publishSkikoJvmRuntimeAndroidArm64PublicationTo"
            val signX64 = "signSkikoJvmRuntimeAndroidX64Publication"
            val signArm64 = "signSkikoJvmRuntimeAndroidArm64Publication"
            val skikoAndroidJar = "skikoAndroidJar"

            when {
                name.startsWith(signAndroid) || name.startsWith(generateMetadata) -> {
                    dependsOn(skikoAndroidJar)
                }
                name.startsWith(publishAndroid) -> {
                    dependsOn(signX64, signArm64)
                }
                name.startsWith(publishX64) -> {
                    dependsOn(signAndroid, signArm64)
                }
                name.startsWith(publishArm64) -> {
                    dependsOn(signX64, signAndroid)
                }
            }
        }
    }

    if (supportNativeLinux) {
        val publishLinuxX64 = "publishLinuxX64PublicationTo"
        val publishLinuxArm64 = "publishLinuxArm64PublicationTo"
        val signLinuxArm64Publication = "signLinuxArm64Publication"
        val signLinuxX64Publication = "signLinuxX64Publication"

        tasks.configureEach {
            when {
                name.startsWith(publishLinuxX64) -> {
                    dependsOn(signLinuxArm64Publication)
                    dependsOn(signLinuxX64Publication)
                }

                name.startsWith(publishLinuxArm64) -> {
                    dependsOn(signLinuxArm64Publication)
                    dependsOn(signLinuxX64Publication)
                }
            }
        }
    }
}

fun KotlinTarget.generateVersion(
    targetOs: OS,
    targetArch: Arch,
    skikoProperties: SkikoProperties,
    compilationName: String = "main"
) {
    val targetName = this.name
    val isUikitSim = isUikitSimulator()
    val generatedDir = project.layout.buildDirectory.dir("generated/$targetName")
    val generateVersionTask = project.registerSkikoTask<DefaultTask>(
        "generateVersion${toTitleCase(platformType.name)}".withSuffix(isUikitSim = isUikitSim),
        targetOs,
        targetArch
    ) {
        inputs.property("buildType", skikoProperties.buildType.id)
        outputs.dir(generatedDir)
        doFirst {
            val outDir = generatedDir.get().asFile
            outDir.deleteRecursively()
            outDir.mkdirs()
            val out = "$outDir/Version.kt"

            val target = "${targetOs.id}-${targetArch.id}"
            val skiaTag = project.skiaVersion(target)

            File(out).writeText(
                """
                package org.jetbrains.skiko
                object Version {
                val skiko = "${skikoProperties.deployVersion}"
                val skia = "$skiaTag"
                }
                """.trimIndent()
            )
        }
    }

    // Needs to be lazily loaded as android compilations are not available right away
    compilations.matching { it.name == compilationName }.configureEach {
        compileTaskProvider.configure {
            dependsOn(generateVersionTask)
            (this as KotlinCompileTool).source(generatedDir.get().asFile)
        }
    }
}