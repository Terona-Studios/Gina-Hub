package io.gina.hub;

import com.destroystokyo.paper.VersionHistoryManager;
import com.destroystokyo.paper.util.VersionFetcher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;

import static net.kyori.adventure.text.Component.text;

/**
 * Gina-Hub's own {@link VersionFetcher} implementation. Gina-Hub is a heavily modified,
 * independently distributed fork of PaperMC: it does not check for updates against Paper's
 * own infrastructure (fill.papermc.io / GitHub), since Gina-Hub's build/version numbering is
 * unrelated to Paper's and that would produce misleading results. This implementation makes
 * no outbound network calls and prints nothing on startup -- build identity and upstream
 * attribution are only shown on demand, via {@code /version}, {@code /about}, and NOTICE.md.
 *
 * <p>The boxed {@code /version} / {@code /about} display is built in {@link
 * io.papermc.paper.command.PaperVersionCommand}; this class only supplies the optional
 * "previous version" history line. {@link VersionFetcher} is a Bukkit/Paper API interface
 * (plugins may look it up via {@code UnsafeValues#getVersionFetcher()}), so that dependency
 * stays as-is; only Gina-Hub's own implementation of it moved out of Paper's package.</p>
 */
@DefaultQualifier(NonNull.class)
public class GinaVersionFetcher implements VersionFetcher {

    @Override
    public long getCacheTime() {
        return 60_000; // no network I/O happens here, but a short cache keeps repeated /version calls cheap
    }

    @Override
    public Component getVersionMessage() {
        final @Nullable Component history = this.getHistory();
        return history != null ? history : Component.empty();
    }

    private @Nullable Component getHistory() {
        final VersionHistoryManager.@Nullable VersionData data = VersionHistoryManager.INSTANCE.getVersionData();
        if (data == null) {
            return null;
        }

        final @Nullable String oldVersion = data.getOldVersion();
        if (oldVersion == null) {
            return null;
        }

        return text("Previous version: " + oldVersion, NamedTextColor.GRAY, TextDecoration.ITALIC);
    }
}
