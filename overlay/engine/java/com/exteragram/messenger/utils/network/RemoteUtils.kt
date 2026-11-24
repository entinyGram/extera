package com.exteragram.messenger.utils.network

import org.telegram.tgnet.TLRPC

object RemoteUtils {
    @JvmStatic
    fun searchMessages(
        account: Int,
        limit: Int,
        query: String,
        filter: TLRPC.MessagesFilter,
        callback: (TLRPC.messages_Messages?, TLRPC.TL_error?) -> Unit,
        timeoutMs: Int,
    ) {
        callback(null, null)
    }
}
