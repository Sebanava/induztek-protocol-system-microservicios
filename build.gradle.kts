plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.spring") version "2.0.21" apply false
    id("org.springframework.boot") version "3.4.12" apply false
}
subprojects {
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
