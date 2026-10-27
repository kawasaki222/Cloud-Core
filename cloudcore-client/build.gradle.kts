import org.gradle.api.tasks.SourceSetContainer

plugins {
    alias(libs.plugins.fabric.loom)
}

dependencies {
    minecraft("com.mojang:minecraft:${libs.versions.minecraft.get()}")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:${libs.versions.fabric.loader.get()}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${libs.versions.fabric.api.get()}")

    implementation(project(":cloudcore-api"))
    implementation(project(":cloudcore-protocol"))

    val lwjglVersion = libs.versions.lwjgl.get()
    implementation("org.lwjgl:lwjgl-nanovg:$lwjglVersion") {
        exclude(group = "org.lwjgl", module = "lwjgl")
    }
    runtimeOnly("org.lwjgl:lwjgl-nanovg:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-nanovg:$lwjglVersion:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-nanovg:$lwjglVersion:natives-macos")
    runtimeOnly("org.lwjgl:lwjgl-nanovg:$lwjglVersion:natives-macos-arm64")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

// The NanoVG compositing spike is opt-in so normal runs never create an extra GL context.
// Use: ./gradlew :cloudcore-client:runClient -PcloudcoreNanoVgSpike=true
// The production vector backend is also opt-in until it is verified on all platforms:
// -PcloudcoreNanoVg=true. Without the flag no system property is set, so config/cloudcore-client.json
// decides (its default is the native backend).
loom {
    runs {
        named("client") {
            property("cloudcore.nanovg.spike",
                    providers.gradleProperty("cloudcoreNanoVgSpike").getOrElse("false"))
            providers.gradleProperty("cloudcoreNanoVg").orNull?.let { value ->
                property("cloudcore.nanovg", value)
            }
        }
    }
}

val apiMain = project(":cloudcore-api").extensions.getByType<SourceSetContainer>()["main"]
val protocolMain = project(":cloudcore-protocol").extensions.getByType<SourceSetContainer>()["main"]

// The distributed mod jar must carry its pure-Java dependencies; Fabric does not resolve
// Gradle project dependencies at runtime.
tasks.jar {
    from(apiMain.output)
    from(protocolMain.output)
}

// Fabric does not resolve Maven runtime dependencies. Bundle the NanoVG classes and the
// three platform natives so a distributed mod jar can initialize the renderer offline.
val nanoVgArtifacts = configurations.runtimeClasspath.get()
        .filter { it.name.startsWith("lwjgl-nanovg-${libs.versions.lwjgl.get()}") }
tasks.jar {
    nanoVgArtifacts.forEach { artifact ->
        from(zipTree(artifact)) {
            exclude("META-INF/**")
            exclude("module-info.class")
        }
    }
}
