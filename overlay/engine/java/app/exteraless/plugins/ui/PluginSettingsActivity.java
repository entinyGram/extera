package app.exteraless.plugins.ui;

import android.content.Context;
import android.view.View;
import app.exteraless.plugins.Plugin;
import app.exteraless.plugins.PluginsController;
import java.util.ArrayList;
import java.util.Collections;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public class PluginSettingsActivity extends desu.inugram.ui.settings.PythonPluginSettingsActivity {
    private Plugin plugin;
    private Object createSubFragmentCallback;
    private boolean searching;
    private String query;
    private Object searchItem;

    public PluginSettingsActivity(String pluginId) {
        this(requirePlugin(pluginId), null);
    }

    public PluginSettingsActivity(Plugin plugin) {
        this(plugin, null);
    }

    public PluginSettingsActivity(Plugin plugin, String target) {
        super(plugin, target, Collections.emptyList(), null);
        this.plugin = plugin;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        super.fillItems(items, adapter);
    }

    @Override
    public View createView(Context context) {
        return super.createView(context);
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        super.onClick(item, view, position, x, y);
    }

    public void updateItems() {
        AndroidUtilities.runOnUIThread(this::reloadRows);
    }

    private static Plugin requirePlugin(String pluginId) {
        Plugin plugin = PluginsController.getInstance().getPlugin(pluginId);
        if (plugin == null) throw new IllegalArgumentException("Plugin is not installed: " + pluginId);
        return plugin;
    }
}
