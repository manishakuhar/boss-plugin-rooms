import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}
group = "ai.rever.boss.plugin.dynamic"
version = "0.1.0"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
repositories { google(); mavenCentral() }
val sdk = files("build/downloaded-deps/boss-plugin-api.jar")
dependencies {
    compileOnly(sdk)
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation(sdk)
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
tasks.jar { archiveClassifier.set("thin") }
tasks.register<Jar>("buildPluginJar") {
    dependsOn(tasks.classes)
    archiveFileName.set("boss-plugin-rooms-${version}.jar")
    from(sourceSets.main.get().output)
}
tasks.build { dependsOn("buildPluginJar") }

val localDemo by sourceSets.creating
configurations[localDemo.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[localDemo.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
dependencies {
    add(localDemo.implementationConfigurationName, sourceSets.main.get().output)
    add(localDemo.implementationConfigurationName, sdk)
    add(localDemo.implementationConfigurationName, "org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}
tasks.register<JavaExec>("runLocalDemo") {
    dependsOn(localDemo.classesTaskName)
    classpath = localDemo.runtimeClasspath
    mainClass.set("ai.rever.boss.plugin.dynamic.rooms.localtest.LocalDemoKt")
    systemProperty("rooms.demo.connection", file(".local-rooms/connection.json").absolutePath)
}
tasks.register<Jar>("buildLocalDemoPlugin") {
    dependsOn(localDemo.classesTaskName)
    archiveFileName.set("boss-plugin-rooms-local-test-${version}.jar")
    from(sourceSets.main.get().output) { exclude("META-INF/boss-plugin/plugin.json") }
    from(localDemo.output)
}

tasks.register("localDemoLaunchConfig") {
    dependsOn(localDemo.classesTaskName)
    doLast {
        file("build/local-demo-classpath.txt").writeText(localDemo.runtimeClasspath.asPath)
        val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }.get()
        file("build/local-demo-java.txt").writeText(launcher.executablePath.asFile.absolutePath)
    }
}
