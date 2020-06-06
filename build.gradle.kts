plugins {
    kotlin("jvm") version "1.3.71"
    kotlin("plugin.serialization") version "1.3.71"
    java
    id("com.github.johnrengelman.shadow") version "5.2.0"
}

group = "yyuueexxiinngg"
version = "0.1.4"

repositories {
    maven(url = "https://mirrors.huaweicloud.com/repository/maven")
    maven(url = "https://dl.bintray.com/kotlin/kotlin-eap")
    jcenter()
    mavenCentral()
}

val miraiCoreVersion: String by rootProject.ext
val miraiConsoleVersion: String by rootProject.ext
val ktorVersion: String by rootProject.ext
val kotlinVersion = "1.3.71"

fun ktor(id: String, version: String = this@Build_gradle.ktorVersion) = "io.ktor:ktor-$id:$version"

dependencies {
    compileOnly(kotlin("stdlib-jdk8"))
    compileOnly("net.mamoe:mirai-core:$miraiCoreVersion")
    compileOnly("net.mamoe:mirai-console:$miraiConsoleVersion")
    compileOnly(kotlin("serialization", kotlinVersion))

    api(ktor("server-cio"))
    api(ktor("client-cio"))
    api(ktor("websockets"))
    api(ktor("client-websockets"))

    api(kotlin("reflect", kotlinVersion))

    testImplementation(kotlin("stdlib-jdk8"))
    testImplementation("net.mamoe:mirai-core:$miraiCoreVersion")
    testImplementation("net.mamoe:mirai-core-qqandroid:$miraiCoreVersion")
    testImplementation("net.mamoe:mirai-console:$miraiConsoleVersion")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }

    val runMiraiConsole by creating(JavaExec::class.java) {
        group = "mirai"
        dependsOn(shadowJar)
        dependsOn(testClasses)

        val testConsoleDir = "test"

        doFirst {
            fun removeOldVersions() {
                File("$testConsoleDir/plugins/").walk()
                    .filter { it.name.matches(Regex("""${project.name}-.*-all.jar""")) }
                    .forEach {
                        it.delete()
                        println("deleting old files: ${it.name}")
                    }
            }

            fun copyBuildOutput() {
                File("build/libs/").walk()
                    .filter { it.name.contains("-all") }
                    .maxBy { it.lastModified() }
                    ?.let {
                        println("Coping ${it.name}")
                        it.inputStream()
                            .transferTo(File("$testConsoleDir/plugins/${it.name}").apply { createNewFile() }
                                .outputStream())
                        println("Copied ${it.name}")
                    }
            }

            workingDir = File(testConsoleDir)
            workingDir.mkdir()
            File(workingDir, "plugins").mkdir()
            removeOldVersions()
            copyBuildOutput()

            classpath = sourceSets["test"].runtimeClasspath
            main = "mirai.RunMirai"
            standardInput = System.`in`
            args(miraiCoreVersion, miraiConsoleVersion)
        }
    }
}