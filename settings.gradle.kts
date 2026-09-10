pluginManagement {
    repositories {
        gradlePluginPortal()
        // https://gitlab.com/weave-mc/weave/-/packages/
        maven("https://gitlab.com/api/v4/projects/80566527/packages/maven")
    }
}

val projectName: String by settings
rootProject.name = projectName
