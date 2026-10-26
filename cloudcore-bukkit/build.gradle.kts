dependencies {
    implementation(project(":cloudcore-api"))
    implementation(project(":cloudcore-protocol"))
    implementation(project(":cloudcore-server-core"))
    compileOnly("io.papermc.paper:paper-api:${libs.versions.paper.api.get()}")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
