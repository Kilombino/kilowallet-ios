package com.kilombino.kilowallet.core.joinblaket

/**
 * A read-only check of the real JoinBlaket market for the iOS CI screenshot run (and anyone who
 * wants to see the directory answer): the directory by its onion with nick authentication, the
 * orderbook as a watcher asks for it, the bonds valued on [chain]. No keys, nothing signed.
 * Reports counts only, never nicks or bond outpoints.
 */
object LiveCheck {
    suspend fun orderbook(transport: DirectoryTransport, chain: () -> ChainLookup, closeChain: (ChainLookup) -> Unit = {},
                          log: (String) -> Unit = {}, attempts: Int = 3): String {
        var last = ""
        for (attempt in 1..attempts) {
            val c = DirectoryClient(JoinBlaketDirectory.HOST, JoinBlaketDirectory.PORT, NickIdentity.random(), JoinBlaketDirectory.NETWORK.wire,
                JoinBlaketDirectory.ID, requireVerified = true, transport = transport, viaTor = true)
            try {
                c.connect()
                log("connected, nick auth ${c.authenticated}, features ${c.directoryFeatures.sorted()}")
                val ob = Orderbook.fetch(c, Orderbook.Timing(minWaitMs = 20_000, quietMs = 10_000, maxWaitMs = 60_000))
                c.close()
                val lookup = chain()
                val valued = try { BondValues.value(ob.offers, lookup) } finally { closeChain(lookup) }
                val pick = MakerSelection.choose(valued, 1_000_000, 9)
                val line = "nick auth ${c.authenticated}; peers ${c.peers.size}; offers ${ob.offerMessages}, bonded ${ob.offers.size}, " +
                    "verified on chain ${valued.count { it.value > 0 }}; 1000000 sats: " + when (pick) {
                        is MakerSelection.Result.Chosen -> "${pick.offers.size} maker(s), fees ${pick.totalFee} sats"
                        is MakerSelection.Result.Failed -> pick.reason
                    }
                log(line)
                return line
            } catch (e: Exception) {
                last = e.message ?: e.toString()
                log("attempt $attempt failed: $last")
                c.close()
            }
        }
        return "failed: $last"
    }
}
