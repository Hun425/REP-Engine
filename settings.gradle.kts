pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "REP-Engine"

include(":common-avro")
include(":simulator")
include(":behavior-consumer")
include(":recommendation-api")
