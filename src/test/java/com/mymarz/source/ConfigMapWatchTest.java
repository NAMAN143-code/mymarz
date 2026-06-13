package com.mymarz.source;

import com.mymarz.core.FieldBinding;
import com.mymarz.core.MarzRegistry;
import com.mymarz.core.SourceStrategyResolver;
import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

/**
 * KAN-100: Kubernetes ConfigMap volumes update by atomically swapping a {@code ..data}
 * directory symlink, which does NOT produce the {@code ENTRY_MODIFY}-on-filename event
 * the old filter matched — so updates only rode the 60s safety net. The source must
 * detect ConfigMap mounts and treat the {@code ..data} swap as a change trigger.
 */
class ConfigMapWatchTest {

    @TempDir
    Path tempDir;

    static class Holder {
        volatile boolean enabled;
    }

    @Test
    @DisplayName("isConfigMapMount: true when a ..data symlink is present, false for a plain directory")
    void detectsConfigMapMount() throws IOException {
        // Plain file directory → not a ConfigMap mount.
        Path plain = tempDir.resolve("plain.yml");
        Files.writeString(plain, "a: 1\n");
        assertThat(FileConfigSource.isConfigMapMount(plain)).isFalse();

        // Kubernetes-style projected volume: a ..data symlink next to the file.
        Path mount = Files.createDirectory(tempDir.resolve("cm"));
        Path dataDir = Files.createDirectory(mount.resolve("..2026_v1"));
        Files.writeString(dataDir.resolve("marz.yml"), "feature:\n  enabled: false\n");
        assumeSymlinks(() -> {
            Files.createSymbolicLink(mount.resolve(FileConfigSource.CONFIGMAP_DATA_LINK), Path.of("..2026_v1"));
            Files.createSymbolicLink(mount.resolve("marz.yml"), Path.of("..data", "marz.yml"));
        });
        assertThat(FileConfigSource.isConfigMapMount(mount.resolve("marz.yml"))).isTrue();
    }

    @Test
    @DisplayName("an atomic ..data symlink swap is applied via the watch path, well within the 60s safety net")
    void configMapSymlinkSwap_appliedByWatch() throws Exception {
        // ── Build the initial ConfigMap layout ─────────────────────────────
        Path mount = Files.createDirectory(tempDir.resolve("cm"));
        Path v1 = Files.createDirectory(mount.resolve("..2026_v1"));
        Files.writeString(v1.resolve("marz.yml"), "feature:\n  enabled: false\n");
        Path file = mount.resolve("marz.yml");
        assumeSymlinks(() -> {
            Files.createSymbolicLink(mount.resolve(FileConfigSource.CONFIGMAP_DATA_LINK), Path.of("..2026_v1"));
            Files.createSymbolicLink(file, Path.of("..data", "marz.yml"));
        });

        MarzRegistry registry = new MarzRegistry(mock(ApplicationEventPublisher.class), new TypeCoercer());
        Holder holder = new Holder();
        Field f = Holder.class.getDeclaredField("enabled");
        f.setAccessible(true);
        registry.register("feature.enabled", new FieldBinding(
                holder, "Holder", "enabled", f, new AtomicReference<>(false),
                boolean.class, "feature.enabled", "file://" + file, false, "false"));

        FileConfigSource source = new FileConfigSource(
                "file://" + file, new ConfigFormatParser(), registry, SourceStrategyResolver.Strategy.WATCHSERVICE);
        assertThat(source.isConfigMapMount()).isTrue();
        assertThat(source.resolve("feature.enabled")).isEqualTo("false"); // read through the symlink

        try {
            source.start();

            // ── Atomic ConfigMap update: new data dir, then swap ..data over it ──
            Path v2 = Files.createDirectory(mount.resolve("..2026_v2"));
            Files.writeString(v2.resolve("marz.yml"), "feature:\n  enabled: true\n");
            Path tmp = mount.resolve("..data_tmp");
            Files.createSymbolicLink(tmp, Path.of("..2026_v2"));
            Files.move(tmp, mount.resolve(FileConfigSource.CONFIGMAP_DATA_LINK),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            // 35s < the 60s safety net, so passing proves the WATCH path caught the swap.
            await().atMost(35, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(holder.enabled).isTrue());
        } finally {
            source.stop();
        }
    }

    private interface SymlinkSetup {
        void run() throws IOException;
    }

    private static void assumeSymlinks(SymlinkSetup setup) {
        try {
            setup.run();
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("Filesystem does not support symbolic links: " + e.getMessage());
        }
    }
}
