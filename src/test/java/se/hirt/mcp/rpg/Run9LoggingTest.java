/* Copyright (C) 2026 Marcus Hirt. SPDX-License-Identifier: BSD-3-Clause */
package se.hirt.mcp.rpg;

import io.quarkus.runtime.configuration.RuntimeConfigBuilder;
import io.quarkus.runtime.configuration.StaticInitConfigBuilder;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("java.lang.System.properties")
class Run9LoggingTest {

	@Test
	void buildPhaseCanResolveTheLogBeforeTheRuntimeUuidExists() throws Exception {
		Path data = Path.of("target", "run9-log-config", "augmentation-data").toAbsolutePath();
		var buildConfig = config(Map.of("rpg.data-dir", data.toString()), false);
		assertTrue(buildConfig.getOptionalValue("quarkus.uuid", String.class).isEmpty(),
				"Quarkus's static-init configuration does not install its runtime UUID source.");
		assertEquals(data.resolve("logs/rpg-mcp-server-build.log"),
				Path.of(buildConfig.getValue("quarkus.log.file.path", String.class)));
		assertEquals("${rpg.data-dir}/logs/rpg-mcp-server-${quarkus.uuid:build}.log",
				buildConfig.getConfigValue("quarkus.log.file.path").getRawValue(),
				"The raw expression remains available for runtime configuration to evaluate again.");
		var runtimeConfig = config(Map.of("rpg.data-dir", data.toString()));
		Path runtimeLog = Path.of(runtimeConfig.getValue("quarkus.log.file.path", String.class));
		assertNotEquals(Path.of(buildConfig.getValue("quarkus.log.file.path", String.class)), runtimeLog);
		assertTrue(runtimeLog.getFileName().toString().contains(runtimeConfig.getValue("quarkus.uuid", String.class)));
	}

	@Test
	void defaultLogIsInsideConfiguredDataDirectoryWithARuntimeUniqueName() throws Exception {
		Path data = Path.of("target", "run9-log-config", "campaign-data").toAbsolutePath();
		var config = config(Map.of("rpg.data-dir", data.toString()));
		Path log = Path.of(config.getValue("quarkus.log.file.path", String.class));
		assertEquals(data.resolve("logs"), log.getParent());
		String file = log.getFileName().toString();
		assertTrue(file.startsWith("rpg-mcp-server-") && file.endsWith(".log"), file);
		String uuid = file.substring("rpg-mcp-server-".length(), file.length() - ".log".length());
		assertEquals(UUID.fromString(uuid).toString(), uuid,
				"The supported Quarkus runtime UUID, not a shared or build-time filename, selects the log.");
		assertEquals(false, config.getValue("quarkus.log.console.enabled", Boolean.class));
		assertEquals(false, config.getValue("quarkus.log.file.rotation.rotate-on-boot", Boolean.class));
	}

	@Test
	void defaultDataAndLogExpressionsResolveToTheSameHomeWithoutOpeningAnyRealLog() throws Exception {
		Path fakeHome = Path.of("target", "run9-log-config", "fake-home").toAbsolutePath();
		var config = config(Map.of("user.home", fakeHome.toString()));
		Path data = Path.of(config.getValue("rpg.data-dir", String.class));
		Path log = Path.of(config.getValue("quarkus.log.file.path", String.class));
		assertEquals(fakeHome.resolve(".rpg-mcp"), data);
		assertEquals(data.resolve("logs"), log.getParent());
	}

	@Test
	void explicitLogPathOverridesTheAutomaticDataLocalPath() throws Exception {
		Path explicit = Path.of("target", "run9-log-config", "chosen.log").toAbsolutePath();
		var config = config(
				Map.of("rpg.data-dir", "target/unused-log-default", "quarkus.log.file.path", explicit.toString()));
		assertEquals(explicit.toString(), config.getValue("quarkus.log.file.path", String.class));
	}

	@Test
	void portableExplicitTildeIsExpandedBeforeBothConfigExpressionsAndDatabaseResolution() throws Exception {
		String priorData = System.getProperty("rpg.data-dir");
		String priorLog = System.getProperty("quarkus.log.file.path");
		try {
			System.setProperty("rpg.data-dir", "~/run9-portable-campaign");
			System.setProperty("quarkus.log.file.path", "target/explicit-retained.log");
			RpgMain.normalizeConfiguredDataDirectory();
			String expanded = RpgConfig.expandHome("~/run9-portable-campaign");
			assertEquals(expanded, System.getProperty("rpg.data-dir"));
			assertEquals("target/explicit-retained.log", System.getProperty("quarkus.log.file.path"));
			var config = config(Map.of("rpg.data-dir", expanded));
			assertEquals(Path.of(expanded), Path.of(config.getValue("rpg.data-dir", String.class)));
		} finally {
			restore("rpg.data-dir", priorData);
			restore("quarkus.log.file.path", priorLog);
		}
	}

	private static SmallRyeConfig config(Map<String, String> overrides) throws Exception {
		return config(overrides, true);
	}

	private static SmallRyeConfig config(Map<String, String> overrides, boolean runtime) throws Exception {
		var properties = new Properties();
		try (var input = Run9LoggingTest.class.getResourceAsStream("/application.properties")) {
			assertNotNull(input);
			properties.load(input);
		}
		var builder = new SmallRyeConfigBuilder();
		if (runtime)
			new RuntimeConfigBuilder().configBuilder(builder);
		else
			new StaticInitConfigBuilder().configBuilder(builder);
		return builder.withProfiles(List.of("prod"))
				.withSources(new PropertiesConfigSource(properties, "application", 100),
						new PropertiesConfigSource(overrides, "isolated-fixture", 1000))
				.build();
	}

	private static void restore(String key, String value) {
		if (value == null)
			System.clearProperty(key);
		else
			System.setProperty(key, value);
	}
}
