plugins { java; id("org.springframework.boot") }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.12"))
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:2024.0.3"))
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway")
}
