dependencies {
    api(project(":cloudcore-api"))
    implementation(project(":cloudcore-protocol"))
    compileOnly(libs.gson)
}
