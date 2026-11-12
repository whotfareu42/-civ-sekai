plugins {
    alias(libs.plugins.paper.userdev)
}

version = "1.0.0"

dependencies {
    paperweight {
        paperDevBundle(libs.versions.paper)
    }
    compileOnly(project(":plugins:essenceglue-paper"))
    compileOnly(project(":plugins:hiddenore-paper"))
    compileOnly(project(":plugins:simpleadminhacks-paper"))
    compileOnly(project(":plugins:factorymod-paper"))
    compileOnly(project(":plugins:realisticbiomes-paper"))
}
