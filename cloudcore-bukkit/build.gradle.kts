import org.gradle.api.tasks.SourceSetContainer

dependencies {
    implementation(project(":cloudcore-api"))
    implementation(project(":cloudcore-protocol"))
    implementation(project(":cloudcore-server-core"))
    compileOnly("io.papermc.paper:paper-api:${libs.versions.paper.api.get()}")
}

val apiMain = project(":cloudcore-api").extensions.getByType<SourceSetContainer>()["main"]
val protocolMain = project(":cloudcore-protocol").extensions.getByType<SourceSetContainer>()["main"]
val serverCoreMain = project(":cloudcore-server-core").extensions.getByType<SourceSetContainer>()["main"]

// The plugin jar is deployed alone; Paper does not resolve Gradle project dependencies.
tasks.jar {
    from(apiMain.output)
    from(protocolMain.output)
    from(serverCoreMain.output)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
