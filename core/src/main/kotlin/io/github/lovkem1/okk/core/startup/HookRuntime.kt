package io.github.lovkem1.okk.core.startup

object HookRuntime {
    private val coordinator = HookInstallCoordinator()

    fun tryPrepareDexKit(): Boolean = coordinator.tryPrepareDexKit()

    fun tryInstallHooks(): Boolean = coordinator.tryInstallHooks()

    fun reset() {
        coordinator.reset()
    }
}
