import org.gradle.api.tasks.SourceSetContainer

dependencies {
    implementation(project(":cloudcore-api"))
    implementation(project(":cloudcore-protocol"))
    implementation(project(":cloudcore-server-core"))
    compileOnly("io.papermc.paper:paper-api:${libs.versions.paper.api.get()}")
    implementation(libs.sqlite.jdbc)
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

// SnakeYAML is used by the page loader and must ship inside the plugin jar: Paper does not
// expose its own copy to plugin classloaders.
val snakeYamlJars = configurations.runtimeClasspath.get()
        .filter { it.name.startsWith("snakeyaml-") }
tasks.jar {
    snakeYamlJars.forEach { artifact ->
        from(zipTree(artifact)) {
            exclude("META-INF/**")
            exclude("module-info.class")
        }
    }
}

// The optional SQLite state backend ships its driver and native libraries in the plugin jar.
val sqliteJars = configurations.runtimeClasspath.get()
        .filter { it.name.startsWith("sqlite-jdbc-") }
tasks.jar {
    sqliteJars.forEach { artifact ->
        from(zipTree(artifact)) {
            exclude("META-INF/**")
            exclude("module-info.class")
        }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
