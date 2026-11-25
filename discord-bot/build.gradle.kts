plugins {
    java
    // Packs everything into one runnable .jar
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.bobbybot"
version = "1.0.0"

repositories {
    mavenCentral()
}

val jdaveVersion = "0.1.8"

dependencies {
    // Discord
    implementation("net.dv8tion:JDA:6.7.0")
    // Discord's voice encryption (DAVE), required for voice since March 2026
    implementation("club.minnced:jdave-api:$jdaveVersion")
    implementation("club.minnced:jdave-native-win-x86-64:$jdaveVersion")
    implementation("club.minnced:jdave-native-darwin:$jdaveVersion")
    implementation("club.minnced:jdave-native-linux-x86-64:$jdaveVersion")
    implementation("club.minnced:jdave-native-linux-aarch64:$jdaveVersion")
    // JSON for Gemini, YAML for the config, readable logs
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.yaml:snakeyaml:2.4")
    implementation("org.slf4j:slf4j-simple:2.0.19")
}

java {
    // JDAVE needs Java 25 (the same Java your Minecraft 26.x server uses)
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveBaseName.set("BobbyBot")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
        attributes(
            "Main-Class" to "com.bobbybot.BobbyBot",
            // JDAVE talks to a native library; this silences Java's warning about it
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
