package com.exteragram.messenger.utils;

public class AppUtils {
    private static volatile Object gson;

    public static Object getGson() {
        Object value = gson;
        if (value == null) {
            try {
                value = Class.forName("com.google.gson.Gson").getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            gson = value;
        }
        return value;
    }
}
