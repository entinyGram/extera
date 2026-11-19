package com.exteragram.messenger;

import org.telegram.messenger.AndroidUtilities;

public class ExteraConfig {
    public static float getAvatarCorners() {
        return AndroidUtilities.dp(100f) / 2f;
    }

    public static void setAvatarCorners(float value) {
    }

    public static int getAvatarCorners(float size) {
        return getAvatarCorners(size, false);
    }

    public static int getAvatarCorners(float size, boolean inPixels) {
        float value = inPixels ? size : AndroidUtilities.dp(size);
        return Math.round(value / 2f);
    }

    public static int getAvatarCorners(float size, boolean inPixels, boolean forum) {
        return getAvatarCorners(size, inPixels);
    }

    public static int getAvatarCorners(float size, boolean inPixels, boolean forum, boolean hasStories) {
        return getAvatarCorners(size, inPixels);
    }
}
