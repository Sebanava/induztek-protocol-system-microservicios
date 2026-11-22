pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "InduztekBackend"
include(":comun", ":identidad", ":protocolos", ":bitacora", ":gateway", ":eureka")
