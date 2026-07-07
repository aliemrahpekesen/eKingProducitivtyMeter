// Placeholder for the automated allowed-dependency enforcement (ArchitectureOverview.md §5
// matrix; BackendPlan.md §1). The authoritative runtime enforcement is the Spring Modulith
// ModularityTests + ArchUnit rules wired in TASK-0005 (module boundaries) and TASK-0002 (the G1
// static stage). Until then, each module's build file declares only its allowed project
// dependencies per the BackendPlan.md §1 table, and this convention is applied so those files can
// reference `id("eip.dependency-rules")` without a later rename.

plugins {
    id("eip.java-conventions")
}
