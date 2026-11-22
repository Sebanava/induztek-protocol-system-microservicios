plugins {
    jacoco
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
}
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies {
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:2024.0.3"))
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")
    runtimeOnly("com.mysql:mysql-connector-j")
    implementation(project(":comun"))
    implementation(enforcedPlatform("org.springframework.boot:spring-boot-dependencies:3.4.12"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testRuntimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test") { exclude(group = "org.mockito") }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required.set(true) }
}
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules { rule { limit { minimum = "0.80".toBigDecimal() } } }
}
tasks.check { dependsOn(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification) }