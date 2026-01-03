plugins {
    kotlin("jvm")
    id("com.github.davidmc24.gradle.plugin.avro")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Avro
    implementation("org.apache.avro:avro:1.11.3")
}

// Avro plugin configuration
avro {
    isCreateSetters.set(false)
    isCreateOptionalGetters.set(true)
    isGettersReturnOptional.set(false)
    isOptionalGettersForNullableFieldsOnly.set(true)
    fieldVisibility.set("PRIVATE")
    outputCharacterEncoding.set("UTF-8")
    stringType.set("String")
}
