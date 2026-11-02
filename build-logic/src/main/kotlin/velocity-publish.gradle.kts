plugins {
    java
    `maven-publish`
}

extensions.configure<PublishingExtension> {
    repositories {
        maven {
            name = "staging"
            url = rootProject.layout.buildDirectory.dir("maven-repository").get().asFile.toURI()
        }
    }
    // A fork must explicitly configure its own publication destination.
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Acceleration")
                description.set("A Velocity fork supporting hierarchical Minecraft proxies")
                url.set("https://github.com/VeguiDev/Acceleration")
                licenses {
                    license {
                        name.set(if (project.name == "acceleration-api") "MIT License" else "GNU General Public License v3.0")
                        url.set(if (project.name == "acceleration-api") "https://opensource.org/license/mit" else "https://www.gnu.org/licenses/gpl-3.0.html")
                    }
                }
                scm {
                    url.set("https://github.com/VeguiDev/Acceleration")
                    connection.set("scm:git:https://github.com/VeguiDev/Acceleration.git")
                    developerConnection.set("scm:git:https://github.com/VeguiDev/Acceleration.git")
                }
            }
        }
    }
}
