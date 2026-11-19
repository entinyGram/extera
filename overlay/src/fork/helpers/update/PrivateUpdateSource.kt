package desu.inugram.helpers.update

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC

object PrivateUpdateSource {
    const val ACTIVE = true

    private const val INVITE_HASH = ""

    /** the signed-in account must already be a member: an invite preview is not enough to read the channel */
    fun resolve(account: Int, done: (Long?) -> Unit) {
        val request = TLRPC.TL_messages_checkChatInvite().apply { hash = INVITE_HASH }
        ConnectionsManager.getInstance(account).sendRequest(request) { response, error ->
            AndroidUtilities.runOnUIThread {
                val chat = (response as? TLRPC.TL_chatInviteAlready)?.chat
                if (chat == null) {
                    FileLog.w("PrivateUpdateSource: not a member of the update channel (${error?.text ?: response?.javaClass?.simpleName})")
                    done(null)
                    return@runOnUIThread
                }
                MessagesController.getInstance(account).putChat(chat, false)
                done(-chat.id)
            }
        }
    }
}
