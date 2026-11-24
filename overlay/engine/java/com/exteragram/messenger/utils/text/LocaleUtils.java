package com.exteragram.messenger.utils.text;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ImageSpan;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

public class LocaleUtils {
    public static Spannable createCopySpan(BaseFragment fragment) {
        SpannableString copyText = new SpannableString(" ");
        if (fragment == null || fragment.getParentActivity() == null) {
            return copyText;
        }
        Drawable copyIcon = ContextCompat.getDrawable(fragment.getParentActivity(), R.drawable.msg_copy);
        if (copyIcon == null) {
            return copyText;
        }
        copyIcon = copyIcon.mutate();
        copyIcon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_undo_cancelColor, fragment.getResourceProvider()), PorterDuff.Mode.SRC_IN));
        copyIcon.setBounds(0, 0, AndroidUtilities.dp(22), AndroidUtilities.dp(22));
        copyText.setSpan(new ImageSpan(copyIcon, ImageSpan.ALIGN_BOTTOM), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return copyText;
    }
}
