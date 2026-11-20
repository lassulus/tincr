package io.thalheim.tincr.ondemand

sealed class Status(val label: String) {
    data object Off : Status("Off")
    data object Starting : Status("Starting…")
    data object Stopping : Status("Turning off…")
    data object Sleeping : Status("Sleeping")
    data object Connecting : Status("Connecting…")
    data class Connected(val peer: String) : Status("Connected to $peer")

    companion object {
        // One request on `ctl`'s session, which wakes tincd: call only
        // while someone is looking.
        fun query(ctl: TincCtl): Status {
            if (!OnDemandService.running) return Off
            if (OnDemandService.stopping) return Stopping
            val conns = ctl.connections() ?: return Starting
            conns.entries.firstOrNull { it.value }?.let { return Connected(it.key) }
            return if (conns.isEmpty()) Sleeping else Connecting
        }
    }
}
