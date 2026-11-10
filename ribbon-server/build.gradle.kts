plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":ribbon-core"))
    implementation("com.graphhopper:graphhopper-core:11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("kr.tedin.maptalkdrive.server.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx6g")
}

tasks.test {
    useJUnitPlatform()
    // `-Pkorea` also runs the smoke tests on the real Korea graph in ribbon-server/data (imported by the server first).
    if (project.hasProperty("korea")) {
        systemProperty("ribbon.korea.data", file("data").absolutePath)
        maxHeapSize = "6g"
    }
}

val osmUrl = "https://download.geofabrik.de/asia/south-korea-latest.osm.pbf"

// Downloads the OSM extract (about 290 MB) once and checks it against Geofabrik's md5. Never runs on its own.
tasks.register<Exec>("downloadOsm") {
    group = "setup"
    description = "Downloads the South Korea OSM extract into ribbon-server/data"
    workingDir = projectDir
    commandLine(
        "sh", "-c",
        "mkdir -p data && cd data && curl -fL -o south-korea-latest.osm.pbf.part $osmUrl && " +
            "curl -fsSL -o south-korea-latest.osm.pbf.md5 $osmUrl.md5 && mv south-korea-latest.osm.pbf.part south-korea-latest.osm.pbf && " +
            "if command -v md5sum >/dev/null; then md5sum -c south-korea-latest.osm.pbf.md5; " +
            "else test \"$(md5 -q south-korea-latest.osm.pbf)\" = \"$(cut -d' ' -f1 south-korea-latest.osm.pbf.md5)\" && echo OK; fi",
    )
}

// Verification only: drives simulated cars over a route the server draws and uploads their knots marked simulated.
tasks.register<JavaExec>("simulate") {
    group = "verification"
    description = "Uploads simulated knots for one route (see sim/Simulate.kt for the arguments)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("kr.tedin.maptalkdrive.server.sim.SimulateKt")
    workingDir = rootProject.projectDir
}
