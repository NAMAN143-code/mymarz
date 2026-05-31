package com.mymarz.demo;

import com.mymarz.source.ConfigFormatParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The "before {@code @Marz}" baseline: a config service that re-opens and
 * re-parses the YAML file on <strong>every single read</strong> so that the
 * latest on-disk value is always returned without a restart.
 *
 * <p>This is the naive way teams achieve "hot" config without a library: it
 * works, but every access pays for a syscall, a full file read, and a YAML
 * parse. {@link #getMaxRequestsPerMinute()} here is the apples-to-apples
 * counterpart of {@link DemoService#getMaxRequestsPerMinute()} — same value,
 * radically different cost. The {@link BenchmarkService} races the two.</p>
 *
 * <p>Every read is instrumented into {@link IoMetrics} so the IO cost shows up
 * in {@code /metrics/io}.</p>
 */
@Service
public class NaiveConfigService {

    private static final Logger log = LoggerFactory.getLogger(NaiveConfigService.class);

    /** Same file the {@code @Marz} fields watch — kept in lock-step on purpose. */
    private final Path configFile;
    private final ConfigFormatParser parser = new ConfigFormatParser();
    private final IoMetrics ioMetrics;

    public NaiveConfigService(
            @Value("${demo.config-file:config/marz-demo.yml}") String configFilePath,
            IoMetrics ioMetrics) {
        this.configFile = Path.of(configFilePath);
        this.ioMetrics = ioMetrics;
        log.info("NaiveConfigService backed by {} — re-reads + re-parses on EVERY access (zero caching)",
                configFile.toAbsolutePath());
    }

    /** Re-read + re-parse the whole file, then pull out the rate-limit key. */
    public int getMaxRequestsPerMinute() {
        Map<String, String> config = readAndParse();
        String raw = config.getOrDefault("rate-limit.max-requests-per-minute", "100");
        return Integer.parseInt(raw.trim());
    }

    /** Re-read + re-parse the whole file, then pull out the feature flag. */
    public boolean isNewCheckoutEnabled() {
        Map<String, String> config = readAndParse();
        return Boolean.parseBoolean(config.getOrDefault("feature.new-checkout.enabled", "false").trim());
    }

    /**
     * The expensive bit: open the file, read all bytes, parse YAML — every call.
     * Each invocation is recorded into {@link IoMetrics} (1 file read + 1 parse).
     */
    private Map<String, String> readAndParse() {
        long start = System.nanoTime();
        try {
            byte[] bytes = Files.readAllBytes(configFile);             // syscall + IO
            String content = new String(bytes);
            Map<String, String> parsed = parser.parse(content, configFile.toString()); // CPU: YAML parse
            ioMetrics.recordNaiveFileRead(bytes.length, System.nanoTime() - start);
            return parsed;
        } catch (IOException e) {
            ioMetrics.recordNaiveFileRead(0, System.nanoTime() - start);
            log.error("Naive read failed for {}: {}", configFile, e.getMessage());
            return Map.of();
        }
    }
}
