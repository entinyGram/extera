package com.exteragram.messenger.utils.chats;

import java.io.File;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;

public class ChatUtils {
    private final int currentAccount;

    private ChatUtils(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    public static ChatUtils getInstance() {
        return new ChatUtils(UserConfig.selectedAccount);
    }

    public static ChatUtils getInstance(int account) {
        return new ChatUtils(account);
    }

    public String getPathToMessage(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) return null;
        File file = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner);
        return file != null && file.exists() ? file.getAbsolutePath() : null;
    }
}
