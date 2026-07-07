// Root aggregator build. Convention plugins live in buildSrc and are applied per module;
// no cross-module wiring lives here (composition is expressed by each module's dependencies).

tasks.register("moduleList") {
    group = "help"
    description = "Prints the declared backend modules (must be exactly the nine eip-* names)."
    val names = subprojects.map { it.name }.sorted()
    doLast { names.forEach { println(it) } }
}
