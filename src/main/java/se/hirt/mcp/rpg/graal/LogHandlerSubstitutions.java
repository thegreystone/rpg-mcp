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
package se.hirt.mcp.rpg.graal;

import java.net.InetAddress;

import javax.net.SocketFactory;

import org.jboss.logmanager.handlers.ClientSocketFactory;
import org.jboss.logmanager.handlers.SocketHandler;
import org.jboss.logmanager.handlers.SyslogHandler;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Native-image substitutions that keep TLS out of the binary.
 * <p>
 * The server never opens a TLS connection: it talks MCP over stdio and stores everything in a local
 * SQLite file. The only references to {@code SSLSocketFactory} in the whole image come from the
 * JBoss LogManager syslog and socket handlers, which Quarkus keeps reachable because
 * {@code quarkus.log.syslog} / {@code quarkus.log.socket} are runtime configuration. Those two
 * references pull the entire JSSE stack, every JCA provider and the JDK trust store into the image,
 * which costs a couple of megabytes per native binary. Substituting the two factory methods makes
 * the handlers still work over plain TCP/UDP and fail with a clear message if someone configures
 * SSL.
 * <p>
 * Only compiled into the native image; on the JVM these classes are inert.
 */
final class LogHandlerSubstitutions {
	private LogHandlerSubstitutions() {
	}

	static UnsupportedOperationException noTls(String handler) {
		return new UnsupportedOperationException(
				"The " + handler + " log handler cannot use SSL in the native image: TLS is not compiled in");
	}
}

@TargetClass(SyslogHandler.class)
final class Target_org_jboss_logmanager_handlers_SyslogHandler {
	@Alias
	private InetAddress serverAddress;
	@Alias
	private int port;
	@Alias
	private SyslogHandler.Protocol protocol;
	@Alias
	private ClientSocketFactory clientSocketFactory;

	@Substitute
	private ClientSocketFactory getClientSocketFactory() {
		if (clientSocketFactory != null) {
			return clientSocketFactory;
		}
		if (protocol == SyslogHandler.Protocol.SSL_TCP) {
			throw LogHandlerSubstitutions.noTls("syslog");
		}
		return ClientSocketFactory.of(SocketFactory.getDefault(), serverAddress, port);
	}
}

@TargetClass(SocketHandler.class)
final class Target_org_jboss_logmanager_handlers_SocketHandler {
	@Alias
	private ClientSocketFactory clientSocketFactory;
	@Alias
	private SocketFactory socketFactory;
	@Alias
	private InetAddress address;
	@Alias
	private int port;
	@Alias
	private SocketHandler.Protocol protocol;

	@Substitute
	private ClientSocketFactory getClientSocketFactory() {
		if (clientSocketFactory != null) {
			return clientSocketFactory;
		}
		if (address == null || port <= 0) {
			throw new IllegalStateException("An address and port greater than 0 is required.");
		}
		if (socketFactory != null) {
			return ClientSocketFactory.of(socketFactory, address, port);
		}
		if (protocol == SocketHandler.Protocol.SSL_TCP) {
			throw LogHandlerSubstitutions.noTls("socket");
		}
		return ClientSocketFactory.of(address, port);
	}
}
