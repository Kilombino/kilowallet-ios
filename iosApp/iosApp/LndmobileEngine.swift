import Foundation
import Lndmobile
import KilowalletApp

/// The Lightning engine: LND linked into the app (Lndmobile.xcframework, bound by gomobile from the
/// same sources as Kilowallet Android's Lndmobile.aar; see lnd-engine/ENGINE). The shared Kotlin code
/// (core/.../ln/Lnd.kt) names each call as Android does ("getInfo", "routerSendPaymentV2"…) and passes
/// serialized protobuf; this file only routes the name to the gomobile function.
enum LndmobileEngine {
    private static let unary: [String: (Data?, LndmobileCallbackProtocol?) -> Void] = [
        "addInvoice": { d, c in LndmobileAddInvoice(d, c) },
        "channelBalance": { d, c in LndmobileChannelBalance(d, c) },
        "closedChannels": { d, c in LndmobileClosedChannels(d, c) },
        "connectPeer": { d, c in LndmobileConnectPeer(d, c) },
        "decodePayReq": { d, c in LndmobileDecodePayReq(d, c) },
        "disconnectPeer": { d, c in LndmobileDisconnectPeer(d, c) },
        "estimateSplice": { d, c in LndmobileEstimateSplice(d, c) },
        "exportAllChannelBackups": { d, c in LndmobileExportAllChannelBackups(d, c) },
        "fundingStateStep": { d, c in LndmobileFundingStateStep(d, c) },
        "getInfo": { d, c in LndmobileGetInfo(d, c) },
        "getState": { d, c in LndmobileGetState(d, c) },
        "getTransactions": { d, c in LndmobileGetTransactions(d, c) },
        "initWallet": { d, c in LndmobileInitWallet(d, c) },
        "listChannels": { d, c in LndmobileListChannels(d, c) },
        "listInvoices": { d, c in LndmobileListInvoices(d, c) },
        "listPayments": { d, c in LndmobileListPayments(d, c) },
        "listPeers": { d, c in LndmobileListPeers(d, c) },
        "listUnspent": { d, c in LndmobileListUnspent(d, c) },
        "lookupInvoice": { d, c in LndmobileLookupInvoice(d, c) },
        "newAddress": { d, c in LndmobileNewAddress(d, c) },
        "offersCreateOffer": { d, c in LndmobileOffersCreateOffer(d, c) },
        "offersDecodeBolt12": { d, c in LndmobileOffersDecodeBolt12(d, c) },
        "offersDisableOffer": { d, c in LndmobileOffersDisableOffer(d, c) },
        "offersEnableOffer": { d, c in LndmobileOffersEnableOffer(d, c) },
        "offersListOffers": { d, c in LndmobileOffersListOffers(d, c) },
        "offersPayOffer": { d, c in LndmobileOffersPayOffer(d, c) },
        "openChannelSync": { d, c in LndmobileOpenChannelSync(d, c) },
        "peersUpdateNodeAnnouncement": { d, c in LndmobilePeersUpdateNodeAnnouncement(d, c) },
        "pendingChannels": { d, c in LndmobilePendingChannels(d, c) },
        "restoreChannelBackups": { d, c in LndmobileRestoreChannelBackups(d, c) },
        "sendCoins": { d, c in LndmobileSendCoins(d, c) },
        "signMessage": { d, c in LndmobileSignMessage(d, c) },
        "stopDaemon": { d, c in LndmobileStopDaemon(d, c) },
        "unlockWallet": { d, c in LndmobileUnlockWallet(d, c) },
        "walletBalance": { d, c in LndmobileWalletBalance(d, c) },
        "walletKitBumpFee": { d, c in LndmobileWalletKitBumpFee(d, c) },
        "walletKitFinalizePsbt": { d, c in LndmobileWalletKitFinalizePsbt(d, c) },
        "walletKitFundPsbt": { d, c in LndmobileWalletKitFundPsbt(d, c) },
        "walletKitListUnspent": { d, c in LndmobileWalletKitListUnspent(d, c) },
        "walletKitReleaseOutput": { d, c in LndmobileWalletKitReleaseOutput(d, c) },
        "watchtowerClientAddTower": { d, c in LndmobileWatchtowerClientAddTower(d, c) },
        "watchtowerClientListTowers": { d, c in LndmobileWatchtowerClientListTowers(d, c) },
    ]

    private static let streams: [String: (Data?, LndmobileRecvStreamProtocol?) -> Void] = [
        "closeChannel": { d, s in LndmobileCloseChannel(d, s) },
        "openChannel": { d, s in LndmobileOpenChannel(d, s) },
        "routerSendPaymentV2": { d, s in LndmobileRouterSendPaymentV2(d, s) },
        "routerTrackPaymentV2": { d, s in LndmobileRouterTrackPaymentV2(d, s) },
        "spliceChannel": { d, s in LndmobileSpliceChannel(d, s) },
        "subscribeInvoices": { d, s in LndmobileSubscribeInvoices(d, s) },
    ]

    static func start(_ args: String, _ listener: IosLndListener) {
        LndmobileStart(args, Callback(listener))
    }

    static func call(_ method: String, _ request: Data?, _ listener: IosLndListener) {
        guard let f = unary[method] else { listener.onError(message: "no such LND call: \(method)"); return }
        f(request ?? Data(), Callback(listener))
    }

    static func stream(_ method: String, _ request: Data?, _ listener: IosLndListener) {
        guard let f = streams[method] else { listener.onError(message: "no such LND stream: \(method)"); return }
        f(request ?? Data(), Callback(listener))
    }

    /// One gomobile callback or stream, handed on to the Kotlin listener.
    private final class Callback: NSObject, LndmobileCallbackProtocol, LndmobileRecvStreamProtocol {
        private let listener: IosLndListener
        init(_ listener: IosLndListener) { self.listener = listener }
        func onResponse(_ p0: Data?) { listener.onResponse(bytes: p0) }
        func onError(_ p0: Error?) { listener.onError(message: p0?.localizedDescription ?? "LND error") }
    }
}
