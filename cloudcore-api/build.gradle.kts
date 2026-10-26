// Pure Java domain model and public API. No Minecraft or Bukkit imports.
dependencies {
    api(libs.gson)
    // Page files are YAML; the loader lives here so server-core and Bukkit share it.
    api(libs.snakeyaml)
}
