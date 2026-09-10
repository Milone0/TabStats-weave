plugins {
    id("net.weavemc.gradle") version "1.4.0"
}

val projectName: String by project
val projectId: String by project
val projectVersion: String by project
val projectGroup: String by project
val mcVersion = property("minecraft.version")?.toString()
    ?: error("minecraft.version is not set")

group = projectGroup
version = projectVersion

weave {
    configure {
        name = projectName
        modId = projectId
        entryPoints = listOf("tabstats.TabStats")
        // Lunar Client runs Minecraft under MCP *named* mappings below 1.16.5, which is also
        // the namespace this source tree is written in, so no remapping of our own code.
        mcpMappings()
    }
    version(mcVersion)
}

repositories {
    mavenCentral()
    maven("https://gitlab.com/api/v4/projects/80566527/packages/maven")
}

dependencies {
    implementation("net.weavemc:loader:1.4.0")
    implementation("net.weavemc:internals:1.4.0")
    implementation("net.weavemc.api:api:1.4.0")
    implementation("net.weavemc.api:api-v1_8:1.4.0")
}

java {
    withSourcesJar()
}

tasks {
    // Built with the local JDK 17 but emitting Java 8 bytecode against the Java 8 API, so no
    // separate JDK 8 toolchain has to be provisioned. (Gradle 9 is incompatible with the
    // foojay resolver version that would do the provisioning.)
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(8)
    }

    jar {
        archiveBaseName.set(projectName)
    }
}
