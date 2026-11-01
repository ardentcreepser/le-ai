package com.chatterbox.voice;

import com.chatterbox.ChatterboxPlugin;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;

/**
 * The only place outside VoiceBridge that touches Simple Voice Chat classes.
 * It's only called when Simple Voice Chat is installed, so the plugin still loads without it.
 */
public final class VoiceHook {

    private VoiceHook() {
    }

    /** Returns the voice bridge, or null if Simple Voice Chat's API isn't available. */
    public static VoiceOutput register(ChatterboxPlugin plugin) {
        BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            return null;
        }
        VoiceBridge bridge = new VoiceBridge(plugin);
        service.registerPlugin(bridge);
        return bridge;
    }
}
