/*
 * Copyright (C) 2026 Marcus Hirt
 *
 * This software is free:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mcp.rpg;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import se.hirt.mcp.rpg.dice.RandomRollService;
import se.hirt.mcp.rpg.dice.RollService;

import java.nio.file.Path;

/**
 * CDI wiring: one {@link Engine} per process, opened on the configured data directory.
 */
@ApplicationScoped
public class EngineProducer {

	private static final Logger LOG = Logger.getLogger(EngineProducer.class);

	@Produces
	@Singleton
	Engine engine(
			RpgConfig config,
			@ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown") String version) {
		Path file = config.dataPath().resolve("rpg.db");
		RollService roller = config.rollSeed().isPresent() ? RandomRollService.seeded(config.rollSeed().getAsLong())
				: new RandomRollService();
		if (config.rollSeed().isPresent()) {
			LOG.warn("rpg.roll-seed is set; dice are reproducible. Do not use this for real play.");
		}
		LOG.infof("Opening RPG database at %s", file.toAbsolutePath());
		return new Engine(file, roller, version);
	}

	void close(@Disposes Engine engine) {
		engine.close();
	}
}
