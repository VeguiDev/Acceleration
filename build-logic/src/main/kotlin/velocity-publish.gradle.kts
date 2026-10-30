plugins {
    java
    `maven-publish`
}

extensions.configure<PublishingExtension> {
    // A fork must explicitly configure its own publication destination.
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Acceleration")
                description.set("A Velocity fork supporting hierarchical Minecraft proxies")
                url.set("https://github.com/VeguiDev/Acceleration")
                scm {
                    url.set("https://github.com/VeguiDev/Acceleration")
                    connection.set("scm:git:https://github.com/VeguiDev/Acceleration.git")
                    developerConnection.set("scm:git:https://github.com/VeguiDev/Acceleration.git")
                }
            }
        }
    }
}
