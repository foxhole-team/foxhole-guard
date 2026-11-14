package com.foxhole.guard.router

internal abstract class RouterPrecheckedSetupFixture : RouterSetupService {
    var precheckCalls = 0
    var finalPlanCalls = 0
    var inspectionCloses = 0
    protected open val compatibilitySystem: RouterSystemInspection get() {
        val catalog = requireNotNull(installCatalog)
        val compact = catalog.compactPlatforms.firstOrNull()
        val variant = catalog.variants.firstOrNull()
        val version = compact?.supportedOpenWrt?.first() ?: requireNotNull(variant).supportedOpenWrt.first()
        val architecture = compact?.architecture ?: requireNotNull(variant).architecture
        val target = compact?.supportedTargets?.first() ?: requireNotNull(variant).supportedTargets.first()
        return RouterSystemInspection(version, RouterPlatform(version.substringBeforeLast('.'), target, architecture),
            RouterResourceFacts(128L * 1024 * 1024, 512L * 1024 * 1024), "6.12.75~fixture-r1")
    }
    private inner class Inspection(override val address: String, val hostKey: ByteArray) : RouterSetupInspection {
        override val system = compatibilitySystem
        override val catalogSha256 = requireNotNull(installCatalog).sha256
        override val manifestSha256 = requireNotNull(installCatalog).manifestSha256
        private var open = true
        override fun requireCurrent() { check(open) }
        override fun close() { if (open) { open = false; inspectionCloses++ } }
    }
    override suspend fun precheck(address: String, hostKey: ByteArray, password: CharArray): RouterSetupInspection {
        precheckCalls++
        password.fill('\u0000')
        return Inspection(address, hostKey.copyOf())
    }
    override suspend fun planInspection(inspection: RouterSetupInspection, selection: RouterInstallProtocolSelection,
        onProgress: (RouterInstallProgress) -> Unit): RouterInstallProposal {
        finalPlanCalls++
        inspection.requireCurrent()
        val actual = inspection as Inspection
        val synthetic = "synthetic-authenticated-session".toCharArray()
        return try { inspect(actual.address, actual.hostKey, synthetic, selection, false, onProgress) }
        finally { synthetic.fill('\u0000') }
    }
}
